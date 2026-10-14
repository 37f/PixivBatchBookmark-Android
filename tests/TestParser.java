import com.openai.pixivbatchbookmark.PixivIdParser;
import java.util.*;
public class TestParser {
  private static void eq(String name, List<String> got, String... expect) {
    List<String> e = Arrays.asList(expect);
    if (!got.equals(e)) throw new RuntimeException(name + " mismatch: " + got + " != " + e);
    System.out.println("PASS " + name + " " + got);
  }
  public static void main(String[] args) {
    eq("BOM and delimited bare IDs", PixivIdParser.extract("\ufeff97003966,103816477；10000001 10000002，97003966"), "97003966","103816477","10000001","10000002");
    eq("numbered list", PixivIdParser.extract("1. 97003966\n2、103816477\n3) 10000001"), "97003966","103816477","10000001");
    eq("long digits never truncated", PixivIdParser.extract("PID: 1234567890123\nhttps://www.pixiv.net/artworks/1234567890123"));
    eq("unrelated hostname rejected", PixivIdParser.extract("https://evilpixiv.net/artworks/97003966\nhttps://www.pixiv.net.evil.com/artworks/97003966\nhttps://evil.example/pixiv.net/artworks/97003966"));
    eq("artist bare list excluded", PixivIdParser.extract("画师\n97003966,103816477\nPID:10000001"), "10000001");
    eq("Unicode authority is not Pixiv", PixivIdParser.extract("https://恶pixiv.net/artworks/97003966\nhttps://www.恶pixiv.net/artworks/97003966\nhttps://pixiv.net@evil.example/artworks/97003966"));
    eq("scheme-less Unicode authority rejected", PixivIdParser.extract("恶pixiv.net/artworks/97003966\npixiv.net/artworks/103816477"), "103816477");
    eq("artist numbered block remains excluded", PixivIdParser.extract("画师\n1. 97003966\n2. 103816477\n3) 10000001"));
    eq("artist numeric block ends at explicit work heading", PixivIdParser.extract("作者\n97003966\n103816477\n作品 ID\n10000001\n10000002"), "10000001","10000002");
    eq("artist context ends at separator", PixivIdParser.extract("画师\n1. 97003966\n2. 103816477\n————\n10000001"), "10000001");
    String text = "画师鉴赏：桃豆こまもち🍡\n\npixiv id：42658805\n————\n💗-pixiv id：97003966\nTapTap\n今日は冷えますねっ…-pixiv id：103816477";
    eq("multiline artist id skipped", PixivIdParser.extract(text), "97003966","103816477");

    eq("same-line artist id skipped", PixivIdParser.extract(
      "画师鉴赏：桃豆こまもち🍡 pixiv id：42658805\n💗-pixiv id：97003966"), "97003966");

    eq("artist user URL skipped", PixivIdParser.extract(
      "作者主页：https://www.pixiv.net/users/42658805\n作品ID：97003966"), "97003966");

    eq("artwork URL accepted", PixivIdParser.extract(
      "https://www.pixiv.net/artworks/97003966"), "97003966");

    eq("explicit PID on artist line accepted", PixivIdParser.extract(
      "画师 桃豆こまもち，PID：97003966"), "97003966");
  }
}
