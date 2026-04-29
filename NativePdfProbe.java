import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.file.*;
import java.util.*;
public class NativePdfProbe {
  public static void main(String[] args) throws Exception {
    for (String rootArg : args) {
      Path root = Paths.get(rootArg);
      System.out.println("== " + root + " ==");
      int nativeOk = 0, total = 0;
      for (Path p : Files.walk(root).filter(Files::isRegularFile).filter(f -> f.toString().toLowerCase().endsWith(".pdf")).sorted().toList()) {
        total++;
        int len = 0;
        try (PDDocument doc = PDDocument.load(p.toFile())) {
          len = new PDFTextStripper().getText(doc).trim().length();
        }
        boolean ok = len >= 100;
        if (ok) nativeOk++;
        System.out.println(p.getFileName() + " | nativeTextLen=" + len + " | nativePdf=" + ok);
      }
      System.out.println("SUMMARY native>=100: " + nativeOk + "/" + total);
    }
  }
}
