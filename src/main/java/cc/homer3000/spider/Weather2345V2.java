package cc.homer3000.spider;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.RateLimiter;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.io.File;
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
public class Weather2345V2 extends AbstractVerticle {

    private static final Logger LOGGER = LoggerFactory.getLogger(Weather2345V2.class);
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.12; rv:57.0) Gecko/20100101 Firefox/57.0";
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String SAVE_SQL = "INSERT INTO weather2345 (city_code, city_name, weather, dt) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE city_name = values(city_name), weather = values(weather)";

    private SQLClient jdbcClient;
    private WebClient webClient;

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        DeploymentOptions options = new DeploymentOptions()
            //.setWorkerPoolSize(100)
            .setWorker(true);
        vertx.deployVerticle(Weather2345V2.class.getName(), options);
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            LOGGER.error("", e);
        }
        try {
            File file = new File("docs/city_list.txt");
            RateLimiter limiter = RateLimiter.create(30);
            Files.readLines(file, Charsets.UTF_8)
                //.subList(0, 10)
                .forEach(e -> {
                    limiter.acquire();
                    String[] arr = e.split(",");
                    JsonObject obj = new JsonObject();
                    obj.put("cityCode", arr[0]);
                    obj.put("cityEnName", arr[1]);
                    obj.put("cityName", arr[4]);
                    vertx.eventBus().send("crawl", obj);
                });
        } catch (Exception e) {
            LOGGER.error("read file error", e);
        }
    }

    @Override
    public void start() throws Exception {
        WebClientOptions options = new WebClientOptions();
        options.setTryUseCompression(true);
        webClient = WebClient.create(vertx, options);
        JsonObject config = new JsonObject()
            .put("driver_class", "com.mysql.jdbc.Driver")
            .put("max_pool_size", 30)
            .put("initial_pool_size", 15)
            .put("url", "jdbc:mysql://localhost:3306/test")
            .put("user", "root")
            .put("password", "123456");
        jdbcClient = JDBCClient.createShared(vertx, config);
        vertx.eventBus().consumer("crawl", (Message<JsonObject> event) -> crawl(event.body()));
        vertx.eventBus().consumer("extract", (Message<JsonObject> event) -> extract(event.body()));
        vertx.eventBus().consumer("save", (Message<JsonObject> event) -> save(event.body()));
    }

    private void crawl(JsonObject obj) {
        String cityCode = obj.getString("cityCode");
        String cityEnName = obj.getString("cityEnName");
        vertx.executeBlocking(event -> {
            LOGGER.info("crawl, cityCode={}", cityCode);
            String url = String.format("http://tianqi.2345.com/%s/%s.htm", cityEnName, cityCode);
            webClient
                .getAbs(url)
                .putHeader("Host", "tianqi.2345.com")
                .putHeader("User-Agent", UA)
                .putHeader("Referer", "http://tianqi.2345.com/seo/sitemap1.htm")
                .putHeader("Accept-Encoding", "gzip, deflate")
                .send(res -> {
                    if (res.succeeded()) {
                        String html = res.result().bodyAsString("GBK");
                        obj.put("crawlResult", html);
                        vertx.eventBus().send("extract", obj);
                    } else {
                        LOGGER.error("crawl error, {}", cityCode, res.cause());
                        vertx.eventBus().send("crawl", obj);
                    }
                });
        }, false, res -> {
            if (!res.succeeded()) {
                LOGGER.error("crawl error, cityCode={}", cityCode, res.cause());
            }
        });
    }

    private void extract(JsonObject obj) {
        String cityCode = obj.getString("cityCode");
        vertx.executeBlocking(event -> {
            LOGGER.info("extract, cityCode={}", cityCode);
            String crawlResult = obj.getString("crawlResult");
            try {
                Optional<JsonObject> opt = extract2(crawlResult);
                if (opt.isPresent()) {
                    obj.put("extractResult", opt.get());
                    vertx.eventBus().send("save", obj);
                }
            } catch (Exception ex) {
                LOGGER.error("extract error, {} {}", cityCode, crawlResult, ex);
            }
        }, false, res -> {
            if (!res.succeeded()) {
                LOGGER.error("extract error, cityCode={}", cityCode, res.cause());
            }
        });
    }

    private Optional<JsonObject> extract2(String crawlResult) {
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

    private void save(JsonObject obj) {
        String cityCode = obj.getString("cityCode");
        vertx.executeBlocking(event -> {
            LOGGER.info("save, cityCode={}", cityCode);
            JsonObject weather = obj.getJsonObject("extractResult");
            String cityName = weather.getString("cityName");
            weather.remove("cityName");
            String json = weather.encode();
            Integer dt = Integer.valueOf(LocalDate.now().format(DTF));
            JsonArray params = new JsonArray().add(cityCode).add(cityName).add(json).add(dt);
            jdbcClient
                .getConnection(rs -> {
                    if (rs.succeeded()) {
                        SQLConnection conn = rs.result();
                        conn.updateWithParams(SAVE_SQL, params, res -> {
                            conn.close();
                            if (res.succeeded()) {
                                LOGGER.info("save ok, {} {}", cityCode, res.result().getUpdated());
                            } else {
                                LOGGER.error("save fail, {}", cityCode, res.cause());
                            }
                        });
                    } else {
                        LOGGER.error("getConnection error, ", rs.cause());
                    }
                });
        }, false, res -> {
            if (!res.succeeded()) {
                LOGGER.error("save error, cityCode={}", cityCode, res.cause());
            }
        });
    }
}
