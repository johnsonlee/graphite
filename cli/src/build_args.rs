//! Walk a forwarded build command line without confusing frontend option values with shell options.
use std::ffi::{OsStr, OsString};
use std::ops::Range;
use std::path::Path;

// Value-taking options in the JVM BuildCommand, plus the language selector owned by the shell.
const VALUE_OPTIONS: &[&str] = &[
    "--lang",
    "--frontend",
    "--package",
    "--project",
    "--scheme",
    "--configuration",
    "--destination",
    "--derived-data",
    "--index-store",
    "--sources",
    "-o",
    "--output",
    "--include",
    "--exclude",
    "--android-sdk",
    "--lib-filter",
    "--fold",
];

pub(crate) struct Arg<'a> {
    pub(crate) range: Range<usize>,
    pub(crate) raw: &'a OsStr,
    pub(crate) name: Option<&'static str>,
    pub(crate) value: Option<&'a OsStr>,
    pub(crate) positional: bool,
}

impl Arg<'_> {
    pub(crate) fn flag(&self, name: &str) -> bool {
        !self.positional && self.name.is_none() && self.raw == name
    }

    pub(crate) fn replacement(&self, value: &Path) -> (usize, OsString) {
        if self.range.len() == 2 {
            (self.range.start + 1, value.into())
        } else {
            let mut joined = OsString::from(self.name.expect("recognized value option"));
            joined.push("=");
            joined.push(value);
            (self.range.start, joined)
        }
    }

    pub(crate) fn without(&self, args: &[OsString]) -> Vec<OsString> {
        [&args[..self.range.start], &args[self.range.end..]].concat()
    }
}

// Split only at an ASCII '=' following a recognized ASCII name. The suffix is not necessarily UTF-8.
fn joined(raw: &OsStr) -> Option<(&'static str, &OsStr)> {
    let bytes = raw.as_encoded_bytes();
    let split = bytes.iter().position(|b| *b == b'=')?;
    let name = VALUE_OPTIONS
        .iter()
        .copied()
        .find(|n| n.as_bytes() == &bytes[..split])?;
    // SAFETY: these bytes come from this OsStr in this process, immediately after a complete
    // ASCII character ('='), a valid boundary in the platform's self-synchronizing encoding.
    let value = unsafe { OsStr::from_encoded_bytes_unchecked(&bytes[split + 1..]) };
    Some((name, value))
}

// A path may start with a dash. Only reserve names that the shell or the
// build frontend actually recognizes, including their joined forms.
fn build_option(value: &OsStr) -> bool {
    let bytes = value.as_encoded_bytes();
    if !bytes.starts_with(b"-") {
        return false;
    }
    let name = bytes.split(|&byte| byte == b'=').next().unwrap_or_default();
    VALUE_OPTIONS.iter().any(|option| name == option.as_bytes())
        || matches!(
            name,
            b"-h"
                | b"--help"
                | b"-V"
                | b"--version"
                | b"-v"
                | b"--verbose"
                | b"--profile"
                | b"--fold-strict"
                | b"--interprocedural"
                | b"--include-libs"
        )
}

pub(crate) fn walk(args: &[OsString]) -> impl Iterator<Item = Arg<'_>> {
    let mut i = 0;
    let mut positional_only = false;
    std::iter::from_fn(move || loop {
        let start = i;
        let raw = args.get(i)?.as_os_str();
        i += 1;
        if !positional_only && raw == "--" {
            positional_only = true;
            continue;
        }
        let positional = positional_only || !raw.as_encoded_bytes().starts_with(b"-");
        let (name, value) = if positional {
            (None, None)
        } else if let Some(name) = VALUE_OPTIONS.iter().copied().find(|n| raw == *n) {
            let value = args
                .get(i)
                .filter(|next| {
                    *next != "--" && !(matches!(name, "-o" | "--output") && build_option(next))
                })
                .map(OsString::as_os_str);
            i += usize::from(value.is_some());
            (Some(name), value)
        } else if let Some((name, value)) = joined(raw) {
            (Some(name), Some(value))
        } else {
            (None, None)
        };
        return Some(Arg {
            range: start..i,
            raw,
            name,
            value,
            positional,
        });
    })
}

fn unique<'a>(
    args: &'a [OsString],
    names: &[&str],
    duplicate: &str,
    missing: &str,
) -> Result<Option<Arg<'a>>, String> {
    let mut found = None;
    for opt in walk(args).filter(|opt| opt.name.is_some_and(|n| names.contains(&n))) {
        if found.is_some() {
            return Err(duplicate.into());
        }
        if opt.value.is_none_or(OsStr::is_empty) {
            return Err(missing.into());
        }
        found = Some(opt);
    }
    Ok(found)
}

pub(crate) fn output_option(args: &[OsString]) -> Result<Option<Arg<'_>>, String> {
    unique(
        args,
        &["-o", "--output"],
        "specify --output only once",
        "--output requires a path (use --output=-name for a path starting with '-')",
    )
}

pub(crate) fn language_option(args: &[OsString]) -> Result<Option<Arg<'_>>, String> {
    unique(
        args,
        &["--lang"],
        "specify --lang only once",
        "--lang requires jvm, js, ts, es or apple",
    )
}

pub(crate) fn frontend_option(args: &[OsString]) -> Result<Option<Arg<'_>>, String> {
    unique(
        args,
        &["--frontend"],
        "specify --frontend only once",
        "--frontend requires an executable",
    )
}

pub(crate) fn requests_help_or_version(args: &[OsString]) -> bool {
    walk(args).any(|a| {
        ["--help", "-h", "--version", "-V"]
            .iter()
            .any(|flag| a.flag(flag))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn os(args: &[&str]) -> Vec<OsString> {
        args.iter().map(OsString::from).collect()
    }

    #[test]
    fn value_options_and_the_separator_shield_flag_like_arguments() {
        for name in [
            "--include",
            "--exclude",
            "--android-sdk",
            "--lib-filter",
            "--fold",
        ] {
            let args = os(&[name, "--lang", "app", "--lang=js", "--", "--lang=ts"]);
            let lang = language_option(&args).unwrap().unwrap();
            assert_eq!(lang.value, Some(OsStr::new("js")));
            assert_eq!(
                lang.without(&args),
                os(&[name, "--lang", "app", "--", "--lang=ts"])
            );
            assert!(output_option(&os(&[name, "-o", "--", "--output=oops"]))
                .unwrap()
                .is_none());
            assert!(!requests_help_or_version(&os(&[
                name, "--help", "--", "-V"
            ])));
        }
        assert!(requests_help_or_version(&os(&["app", "-V"])));
    }

    #[test]
    fn output_forms_agree_on_missing_paths_duplicates_and_replacement() {
        for (args, expected) in [
            (os(&["app", "-o", "a"]), os(&["app", "-o", "b"])),
            (os(&["app", "--output", "a"]), os(&["app", "--output", "b"])),
            (os(&["app", "--output=a"]), os(&["app", "--output=b"])),
            (os(&["app", "-o=a"]), os(&["app", "-o=b"])),
        ] {
            let option = output_option(&args).unwrap().unwrap();
            assert_eq!(option.without(&args), os(&["app"]));
            let (i, arg) = option.replacement(Path::new("b"));
            let mut rewritten = args;
            rewritten[i] = arg;
            assert_eq!(rewritten, expected);
        }
        for path in ["-1.graphite", "-graphs", "--user-file", "-"] {
            let args = os(&["app.jar", "-o", path]);
            assert_eq!(
                output_option(&args).unwrap().unwrap().value,
                Some(OsStr::new(path))
            );
        }
        for joined in ["-o=--help", "--output=--help"] {
            let args = os(&["input", joined]);
            assert_eq!(
                output_option(&args).unwrap().unwrap().value,
                Some(OsStr::new("--help"))
            );
            assert!(!requests_help_or_version(&args));
        }
        for a in [
            os(&["app", "-o", "--help"]),
            os(&["app", "--output", "-V"]),
            os(&["app", "-o"]),
            os(&["--output", "--", "a"]),
            os(&["--output="]),
            os(&["-o="]),
        ] {
            assert_eq!(
                output_option(&a).err().as_deref(),
                Some("--output requires a path (use --output=-name for a path starting with '-')")
            );
        }
        assert_eq!(
            output_option(&os(&["--output=a", "app", "-o", "b"]))
                .err()
                .as_deref(),
            Some("specify --output only once")
        );
    }

    #[cfg(unix)]
    #[test]
    fn joined_paths_preserve_non_unicode_bytes() {
        use std::os::unix::ffi::OsStringExt;
        let path = OsString::from_vec(b"app-\xff.graphite".to_vec());
        let next = OsString::from_vec(b"stage-\xfe".to_vec());
        let mut joined = OsString::from("--output=");
        joined.push(&path);
        let args = vec![OsString::from("app.jar"), joined];
        let option = output_option(&args).unwrap().unwrap();
        assert_eq!(option.value, Some(path.as_os_str()));
        let (i, replacement) = option.replacement(Path::new(&next));
        assert_eq!(i, 1);
        let mut expected = OsString::from("--output=");
        expected.push(next);
        assert_eq!(replacement, expected);
        let invalid = OsString::from_vec(b"--lang=\xff".to_vec());
        assert!(crate::build_ts::select_language(&[invalid]).is_err());
    }
}
