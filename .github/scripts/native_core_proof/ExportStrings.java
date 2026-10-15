import it.unimi.dsi.util.FrontCodedStringList;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Dependency-only bridge for Java serialization; no Graphite reader/property API. */
public final class ExportStrings {
    /** Count bytes delivered to ObjectInputStream, outside the buffered file reader.
     * One-byte bulk reads prevent ObjectInputStream's block buffer from reading ahead.
     */
    static final class CountedInput extends FilterInputStream {
        long count;
        CountedInput(InputStream input) { super(input); }
        public int read() throws IOException {
            int value=in.read(); if(value!=-1)count++; return value;
        }
        public int read(byte[] bytes,int offset,int length) throws IOException {
            int n=in.read(bytes,offset,Math.min(length,1)); if(n>0)count+=n; return n;
        }
        public long skip(long n) throws IOException {
            long skipped=0;
            while(skipped<n && read()!=-1)skipped++;
            return skipped;
        }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    static String hash(InputStream input) throws Exception {
        try (input) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] block=new byte[65536]; int n;
            while((n=input.read(block))!=-1) digest.update(block,0,n);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
    static String hash(Path path) throws Exception { return hash(Files.newInputStream(path)); }
    public static void main(String[] args) throws Exception {
        require(args.length==4,"usage: ExportStrings graph.strings expected-input-sha new-output-dir ExportStrings.java");
        Path input=Path.of(args[0]), output=Path.of(args[2]), source=Path.of(args[3]);
        String inputSha=hash(input); require(inputSha.equals(args[1]),"input SHA mismatch before export");
        Files.createDirectory(output);
        FrontCodedStringList strings;
        // Required ordering: OIS -> counted single-byte reader -> buffered file reader.
        // JDK17's successful top-level readObject0 does not peek a subsequent token.
        try(CountedInput counted=new CountedInput(new BufferedInputStream(Files.newInputStream(input),65536));
            ObjectInputStream objects=new ObjectInputStream(counted)) {
            Object value=objects.readObject();
            require(value!=null && value.getClass()==FrontCodedStringList.class,"unexpected serialized string object/subclass");
            long boundary=counted.count;
            try {
                objects.readObject();
                throw new IllegalArgumentException("second serialized object, including null");
            } catch(EOFException eof) {
                require(counted.count==boundary,"truncated trailing serialized object/token");
            }
            strings=(FrontCodedStringList)value;
        }
        MessageDigest semantic=MessageDigest.getInstance("SHA-256");
        semantic.update(ByteBuffer.allocate(4).putInt(strings.size()).array());
        String previous=null;
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output.resolve("strings.bin"),StandardOpenOption.CREATE_NEW)))) {
            out.writeInt(0x47534f01); out.writeInt(strings.size());
            for(int index=0;index<strings.size();index++) {
                String value=strings.get(index).toString();
                require(previous==null || previous.compareTo(value)<0,"string order/duplicate"); previous=value;
                ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
                byte[] bytes=new byte[encoded.remaining()]; encoded.get(bytes);
                out.writeInt(bytes.length); out.write(bytes);
                semantic.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); semantic.update(bytes);
            }
        }
        require(hash(input).equals(inputSha),"input changed during export");
        String classSha=hash(ExportStrings.class.getResourceAsStream("ExportStrings.class"));
        String dependencySha=hash(FrontCodedStringList.class.getResourceAsStream("FrontCodedStringList.class"));
        String json="{\n  \"format\": \"GSO01\",\n  \"inputSha256\": \""+inputSha+"\",\n"+
            "  \"outputSha256\": \""+hash(output.resolve("strings.bin"))+"\",\n"+
            "  \"entryCount\": "+strings.size()+",\n  \"semanticSha256\": \""+HexFormat.of().formatHex(semantic.digest())+"\",\n"+
            "  \"helperClassSha256\": \""+classSha+"\",\n  \"helperSourceSha256\": \""+hash(source)+"\",\n"+
            "  \"frontCodedStringListClassSha256\": \""+dependencySha+"\"\n}\n";
        Files.writeString(output.resolve("receipt.json"),json,StandardOpenOption.CREATE_NEW);
    }
}
