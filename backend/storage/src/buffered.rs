//! Load-scoped positional reads. These buffers never become part of a loaded graph.
use std::fs::File;
use std::io;
use std::sync::Arc;

const WINDOW_BYTES: usize = 64 * 1024;

pub(crate) trait ReadRange {
    fn len(&self) -> u64;
    fn read_at(&self, offset: u64, dest: &mut [u8]) -> io::Result<usize>;
}

/// An entry-relative view of the same open file used to create its query map.
pub(crate) struct FileRange {
    file: Arc<File>,
    start: u64,
    len: u64,
    pub(crate) name: String,
}

impl FileRange {
    pub(crate) fn new(file: Arc<File>, start: u64, len: u64, name: String) -> Self {
        Self {
            file,
            start,
            len,
            name,
        }
    }
}

impl ReadRange for FileRange {
    fn len(&self) -> u64 {
        self.len
    }

    fn read_at(&self, offset: u64, dest: &mut [u8]) -> io::Result<usize> {
        let remaining = self
            .len
            .checked_sub(offset)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "outside entry range"))?;
        let count = dest
            .len()
            .min(usize::try_from(remaining).unwrap_or(usize::MAX));
        let absolute = self
            .start
            .checked_add(offset)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "entry offset overflow"))?;
        // Production entry extents are already checked by the directory/container
        // source. Still reject invalid private ranges before issuing any OS read.
        absolute
            .checked_add(count as u64)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "read span overflow"))?;
        self.start
            .checked_add(self.len)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "entry extent overflow"))?;
        if count == 0 {
            return Ok(0);
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::FileExt;
            self.file.read_at(&mut dest[..count], absolute)
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::FileExt;
            // Windows updates the handle cursor, but this call uses the explicit
            // absolute offset. No loader read depends on that shared cursor.
            self.file.seek_read(&mut dest[..count], absolute)
        }
    }
}

/// Fixed entry-relative window, including reads spanning a window or an entry end.
/// A short/Interrupted OS read is retried; premature EOF and other I/O errors fail load.
pub(crate) struct ReadWindow<R> {
    pub(crate) source: R,
    bytes: Box<[u8]>,
    start: u64,
    used: usize,
}

impl<R: ReadRange> ReadWindow<R> {
    pub(crate) fn new(source: R) -> Self {
        Self {
            source,
            bytes: vec![0; WINDOW_BYTES].into_boxed_slice(),
            start: 0,
            used: 0,
        }
    }

    pub(crate) fn copy_prefix(&mut self, offset: u64, dest: &mut [u8]) -> io::Result<usize> {
        let remaining =
            self.source.len().checked_sub(offset).ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidInput, "outside entry range")
            })?;
        let count = dest
            .len()
            .min(usize::try_from(remaining).unwrap_or(usize::MAX));
        let mut copied = 0;
        while copied < count {
            let at = offset + copied as u64; // bounded above by the entry length
            let cached = at
                .checked_sub(self.start)
                .and_then(|n| usize::try_from(n).ok())
                .filter(|&n| n < self.used);
            let local = match cached {
                Some(local) => local,
                None => {
                    self.start = at / WINDOW_BYTES as u64 * WINDOW_BYTES as u64;
                    self.used = 0;
                    let wanted = usize::try_from(self.source.len() - self.start)
                        .unwrap_or(usize::MAX)
                        .min(WINDOW_BYTES);
                    while self.used < wanted {
                        match self.source.read_at(
                            self.start + self.used as u64,
                            &mut self.bytes[self.used..wanted],
                        ) {
                            Ok(0) => {
                                return Err(io::Error::new(
                                    io::ErrorKind::UnexpectedEof,
                                    "entry shortened during load",
                                ))
                            }
                            Ok(n) if n <= wanted - self.used => self.used += n,
                            Ok(_) => {
                                return Err(io::Error::new(
                                    io::ErrorKind::InvalidData,
                                    "read exceeded requested range",
                                ))
                            }
                            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                            Err(e) => return Err(e),
                        }
                    }
                    (at - self.start) as usize
                }
            };
            let n = (count - copied).min(self.used - local);
            dest[copied..copied + n].copy_from_slice(&self.bytes[local..local + n]);
            copied += n;
        }
        Ok(copied)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::{Cell, RefCell};

    fn file_fixture(name: &str, bytes: &[u8]) -> (std::path::PathBuf, Arc<File>) {
        let path =
            std::env::temp_dir().join(format!("graphite-range-{name}-{}", std::process::id()));
        std::fs::write(&path, bytes).unwrap();
        let file = Arc::new(File::open(&path).unwrap());
        (path, file)
    }

    #[test]
    fn file_ranges_apply_nonzero_start_clip_and_reject_absolute_overflow() {
        let (path, file) = file_fixture("bounds", b"prefixENTRYsuffix");
        let range = FileRange::new(file.clone(), 6, 5, "entry".into());
        let mut bytes = [255; 12];
        assert_eq!(range.read_at(1, &mut bytes).unwrap(), 4);
        assert_eq!(&bytes[..4], b"NTRY");
        assert!(bytes[4..].iter().all(|&b| b == 255));
        assert_eq!(range.read_at(5, &mut bytes).unwrap(), 0);
        assert_eq!(
            range.read_at(6, &mut bytes).unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
        // Cover start+offset, start+length and an absolute read crossing u64::MAX.
        for (start, length, offset, size) in [
            (u64::MAX, 2, 1, 1usize),
            (u64::MAX - 1, 3, 0, 3),
            (u64::MAX - 1, 3, 2, 0),
        ] {
            let invalid = FileRange::new(file.clone(), start, length, "invalid".into());
            assert_eq!(
                invalid
                    .read_at(offset, &mut bytes[..size])
                    .unwrap_err()
                    .kind(),
                io::ErrorKind::InvalidInput
            );
        }
        drop(range);
        drop(file);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn shared_file_readers_use_entry_offsets_independently_of_the_seek_cursor() {
        use std::io::{Seek, SeekFrom};
        use std::sync::Barrier;
        let payload: Vec<_> = (0..1024).map(|i| (i % 251) as u8).collect();
        let (path, file) = file_fixture("concurrent", &payload);
        let mut shared_cursor = &*file;
        shared_cursor.seek(SeekFrom::Start(7)).unwrap();
        let barrier = Barrier::new(2);
        std::thread::scope(|scope| {
            for start in [17u64, 601] {
                let range = FileRange::new(file.clone(), start, 127, "entry".into());
                let expected = &payload[start as usize..start as usize + 127];
                let barrier = &barrier;
                scope.spawn(move || {
                    barrier.wait();
                    for round in 0..64 {
                        let offset = round % 31;
                        let mut bytes = [0; 23];
                        assert_eq!(
                            range.read_at(offset as u64, &mut bytes).unwrap(),
                            bytes.len()
                        );
                        assert_eq!(&bytes, &expected[offset..offset + bytes.len()]);
                    }
                });
            }
        });
        // Unix positional reads additionally leave the shared cursor unchanged.
        // Windows seek_read updates it, without using it to choose the read offset.
        #[cfg(unix)]
        assert_eq!(shared_cursor.stream_position().unwrap(), 7);
        drop(file);
        std::fs::remove_file(path).unwrap();
    }

    struct RecordingRange {
        bytes: Vec<u8>,
        reported_len: u64,
        chunk: usize,
        interrupt: Cell<bool>,
        calls: RefCell<Vec<(u64, usize)>>,
    }

    impl ReadRange for RecordingRange {
        fn len(&self) -> u64 {
            self.reported_len
        }
        fn read_at(&self, offset: u64, dest: &mut [u8]) -> io::Result<usize> {
            self.calls.borrow_mut().push((offset, dest.len()));
            if self.interrupt.replace(false) {
                return Err(io::ErrorKind::Interrupted.into());
            }
            let bytes = self.bytes.get(offset as usize..).unwrap_or_default();
            let n = bytes.len().min(dest.len()).min(self.chunk);
            dest[..n].copy_from_slice(&bytes[..n]);
            Ok(n)
        }
    }

    fn source(len: usize) -> RecordingRange {
        RecordingRange {
            bytes: (0..len).map(|i| (i % 251) as u8).collect(),
            reported_len: len as u64,
            chunk: usize::MAX,
            interrupt: Cell::new(false),
            calls: RefCell::new(Vec::new()),
        }
    }

    #[test]
    fn windows_are_bounded_and_copy_across_boundaries_and_entry_end() {
        let mut reader = ReadWindow::new(source(WINDOW_BYTES * 2 + 7));
        let mut bytes = [0; 12];
        let at = WINDOW_BYTES - 5;
        assert_eq!(reader.copy_prefix(at as u64, &mut bytes).unwrap(), 12);
        assert_eq!(&bytes, &reader.source.bytes[at..at + 12]);
        assert_eq!(
            &*reader.source.calls.borrow(),
            &[(0, WINDOW_BYTES), (WINDOW_BYTES as u64, WINDOW_BYTES)]
        );
        reader
            .copy_prefix((WINDOW_BYTES + 2) as u64, &mut bytes)
            .unwrap();
        assert_eq!(reader.source.calls.borrow().len(), 2);
        let at = WINDOW_BYTES * 2 + 3;
        bytes.fill(255);
        assert_eq!(reader.copy_prefix(at as u64, &mut bytes).unwrap(), 4);
        assert_eq!(&bytes[..4], &reader.source.bytes[at..]);
        assert_eq!(&bytes[4..], &[255; 8]);
        assert_eq!(
            reader.source.calls.borrow().last(),
            Some(&((WINDOW_BYTES * 2) as u64, 7))
        );
        assert_eq!(
            reader.copy_prefix(reader.source.len(), &mut bytes).unwrap(),
            0
        );
        assert_eq!(
            reader
                .copy_prefix(reader.source.len() + 1, &mut bytes)
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[test]
    fn interrupted_and_short_reads_preserve_bytes_and_premature_eof_is_an_error() {
        let mut input = source(29);
        input.chunk = 3;
        input.interrupt.set(true);
        let mut reader = ReadWindow::new(input);
        let mut bytes = [0; 29];
        assert_eq!(reader.copy_prefix(0, &mut bytes).unwrap(), 29);
        assert_eq!(bytes.as_slice(), reader.source.bytes);
        assert!(reader.source.calls.borrow().len() > 10);
        let mut input = source(8);
        input.reported_len = 9;
        let mut reader = ReadWindow::new(input);
        assert_eq!(
            reader.copy_prefix(0, &mut bytes).unwrap_err().kind(),
            io::ErrorKind::UnexpectedEof
        );
    }

    #[test]
    fn io_errors_propagate_without_conversion_to_missing_data() {
        struct Failed;
        impl ReadRange for Failed {
            fn len(&self) -> u64 {
                8
            }
            fn read_at(&self, _: u64, _: &mut [u8]) -> io::Result<usize> {
                Err(io::ErrorKind::PermissionDenied.into())
            }
        }
        let mut reader = ReadWindow::new(Failed);
        assert_eq!(
            reader.copy_prefix(0, &mut [0; 8]).unwrap_err().kind(),
            io::ErrorKind::PermissionDenied
        );
    }
}
