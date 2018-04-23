package cc.homer3000.spider;

import com.google.common.base.Charsets;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import java.io.File;
import java.nio.charset.Charset;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jianghuipeng
 * @date 2018/4/22
 */
public class Weather2345Test {

    private static final Logger LOGGER = LoggerFactory.getLogger(Weather2345Test.class);

    @Test
    public void test() {
        try {
            File infile = new File("docs/city_list_data.txt");
            String str = Files.toString(infile, Charsets.UTF_8);
            Pattern pattern = Pattern.compile("(\\d+)-\\w (.*?)-\\d+");
            Matcher matcher = pattern.matcher(str);
            StringBuilder sb = new StringBuilder();
            Set<String> cityCodeSet = Sets.newHashSet();
            while (matcher.find()) {
                String code = matcher.group(1);
                String name = matcher.group(2);
                if (code.length() < 5 || cityCodeSet.contains(code)) {
                    System.out.println(code + "," + name);
                    continue;
                }
                sb.append(code).append(',').append(name).append('\n');
                cityCodeSet.add(code);
            }
            File outfile = new File("docs/2345_city_list.txt");
            Files.write(sb, outfile, Charsets.UTF_8);
            System.out.println("city list size: " + cityCodeSet.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void getCityList() {
        try {
            File infile = new File("docs/sitemap.html");
            File outfile = new File("docs/city_list.txt");
            Document doc = Jsoup.parse(infile, "GBK");
            StringBuilder sb = new StringBuilder();
            for (Element prov : doc.select(".mod_h")) {
                String provName = removeEnd(prov.select(".hd a").first().text(), 2);
                for (Element city : prov.select(".map_list")) {
                    Element c = city.select("dt a").first();
                    //String cityHref = c.attr("href");
                    String cityName = removeEnd(c.text(), 2);
                    for (Element county : city.select("dd a")) {
                        String countyHref = county.attr("href");
                        String countyName = removeEnd(county.text(), 2);
                        //LOGGER.info("{} {} {} {}", countyHref, getCountyCode(countyHref), getCountyEnName(countyHref), countyName);
                        sb
                            .append(getCountyCode(countyHref)).append(',')
                            .append(getCountyEnName(countyHref)).append(',')
                            .append(provName).append(',')
                            .append(cityName).append(',')
                            .append(countyName).append('\n');
                    }
                }
            }
            Files.write(sb.toString(), outfile, Charset.forName("UTF-8"));
        } catch (Exception e) {
            LOGGER.error("", e);
        }
    }

    private String removeEnd(String str, int length) {
        return str.substring(0, str.length() - length);
    }

    private String getCountyEnName(String href) {
        int idx = href.indexOf('/', 1);
        return href.substring(1, idx);
    }

    private String getCountyCode(String href) {
        int startIdx = href.lastIndexOf('/');
        int endIdx = href.lastIndexOf('.');
        return href.substring(startIdx + 1, endIdx);
    }
}
