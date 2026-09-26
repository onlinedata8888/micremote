package com.example.tvremote;
import java.nio.file.*;
public class MdnsTest {
    public static void main(String[] a) throws Exception {
        String[] f = {"p0","p1","p2","other"};
        for (String n : f) { byte[] b = Files.readAllBytes(Paths.get("/tmp/mdns/" + n + ".bin")); System.out.println(n + " -> [" + Mdns.parseInstance(b, b.length) + "]"); }
        byte[] q = Mdns.query();
        Files.write(Paths.get("/tmp/mdns/query_mine.bin"), q);
        System.out.println("query as response -> " + Mdns.parseInstance(q, q.length) + " (must be null)");
        byte[] junk = new byte[]{1,2,3}; System.out.println("junk -> " + Mdns.parseInstance(junk, 3));
    }
}
