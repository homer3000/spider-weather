package cc.homer3000.spider;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.RateLimiter;
import io.reactivex.schedulers.Schedulers;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.jdbc.JDBCClient;
import io.vertx.reactivex.ext.sql.SQLClient;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jianghuipeng
 * @date 2018/4/21
 */
public class Weather2345V3 {

    private static final Logger LOGGER = LoggerFactory.getLogger(Weather2345V3.class);
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.12; rv:57.0) Gecko/20100101 Firefox/57.0";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String SAVE_SQL = "INSERT INTO weather2345 (city_code, city_name, weather, dt) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE city_name = values(city_name), weather = values(weather)";

    private static SQLClient jdbcClient;
    private static WebClient webClient;
    private static Vertx vertx = Vertx.vertx();

    public static void main(String[] args) throws IOException {
        init();
        RateLimiter limiter = RateLimiter.create(5);
        Files.readLines(new File("docs/city_list.txt"), Charsets.UTF_8)
            .subList(0, 100)
            .forEach(e -> {
                limiter.acquire();
                String[] arr = e.split(",");
                JsonObject obj = new JsonObject();
                obj.put("cityCode", arr[0]);
                obj.put("cityEnName", arr[1]);
                crawl(obj);
            });
    }

    private static void init() {
        WebClientOptions options = new WebClientOptions();
        options.setTryUseCompression(true);
        webClient = WebClient.create(vertx, options);
        JsonObject config = new JsonObject()
            .put("driver_class", "com.mysql.jdbc.Driver")
            .put("max_pool_size", 30)
            .put("initial_pool_size", 1)
            .put("url", "jdbc:mysql://localhost:3306/test")
            .put("user", "root")
            .put("password", "123456");
        jdbcClient = JDBCClient.createShared(vertx, config);
    }

    private static void crawl(JsonObject obj) {
        String cityCode = obj.getString("cityCode");
        String cityEnName = obj.getString("cityEnName");
        LOGGER.info("crawl, cityCode={}", cityCode);
        String url = String.format("http://tianqi.2345.com/%s/%s.htm", cityEnName, cityCode);
        webClient.getAbs(url)
            .putHeader("Host", "tianqi.2345.com")
            .putHeader("User-Agent", UA)
            .putHeader("Referer", "http://tianqi.2345.com/seo/sitemap1.htm")
            .putHeader("Accept-Encoding", "gzip, deflate")
            .rxSend()
            .observeOn(Schedulers.io())
            .subscribe(res -> {
                String resp = res.bodyAsString("GBK");
                obj.put("crawlResult", resp);
                Optional<JsonObject> opt = extract(obj);
                if (opt.isPresent()) {
                    obj.put("extractResult", opt.get());
                    save(obj);
                }
            });
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        } catch (InterruptedException e) {
            LOGGER.error("", e);
        }
    }

    private static Optional<JsonObject> extract(JsonObject obj) {
        String crawlResult = obj.getString("crawlResult");
        JsonObject weather = new JsonObject();
        Document doc = Jsoup.parse(crawlResult);
        Elements info = doc.select("#weaLiveInfo li");
        if (info.isEmpty()) {
            return Optional.empty();
        }
        for (Element li : info) {
            String[] arr = li.text().split("：");
            if ("空气质量".equals(arr[0])) {
                continue;
            }
            weather.put(arr[0], arr[1]);
        }
        String cityName = doc.select(".btitle h1").first().text();
        cityName = cityName.substring(0, cityName.length() - 4);
        weather.put("cityName", cityName);
        Element charact = doc.select(".charact a").first();
        String temperature = charact.select("i").first().text();
        String wea = charact.text().split(" ")[0];
        String note = doc.select("#emoticonId").first().text();
        weather.put("天气", wea);
        weather.put("温度", temperature);
        weather.put("提示语", note);
        return Optional.of(weather);
    }

    private static void save(JsonObject obj) {
        String cityCode = obj.getString("cityCode");
        LOGGER.info("save, cityCode={}", cityCode);
        jdbcClient
            .rxGetConnection()
            .observeOn(Schedulers.io())
            .subscribe(conn -> {
                    JsonObject weather = obj.getJsonObject("extractResult");
                    String cityName = weather.getString("cityName");
                    weather.remove("cityName");
                    String json = weather.encode();
                    Integer dt = Integer.valueOf(LocalDate.now().format(DTF));
                    JsonArray params = new JsonArray()
                        .add(cityCode).add(cityName)
                        .add(json).add(dt);
                    conn.rxUpdateWithParams(SAVE_SQL, params)
                        .observeOn(Schedulers.io())
                        .subscribe(result -> {
                            conn.close();
                            LOGGER.info("save ok, {} {}", cityCode, result.getUpdated());
                        });
                }
            );
    }
}
