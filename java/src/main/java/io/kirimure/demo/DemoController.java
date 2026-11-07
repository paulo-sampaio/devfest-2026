package io.kirimure.demo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
public class DemoController {

    public record City(
        String id,
        String name,
        String country,
        String flag,
        String timezone,
        String fact
    ) {}

    private static final List<City> CITIES = List.of(
        new City("salvador", "Salvador", "Brasil", "🇧🇷", "America/Bahia",
            "Primeira capital do Brasil (1549) e primeira cidade planejada das Américas no período colonial português."),
        new City("kyiv", "Kyiv", "Ucrânia", "🇺🇦", "Europe/Kyiv",
            "Capital do Rus de Kyiv há mais de 1.500 anos, com a Catedral de Santa Sofia (século XI) mantendo mosaicos originais intactos."),
        new City("varanasi", "Varanasi", "Índia", "🇮🇳", "Asia/Kolkata",
            "Uma das cidades continuamente habitadas mais antigas do mundo (mais de 3.000 anos), sagrada às margens do Rio Ganges."),
        new City("xian", "Xi'an", "China", "🇨🇳", "Asia/Shanghai",
            "Ponto de partida oriental da histórica Rota da Seda e lar do lendário Exército de Terracota com mais de 8.000 guerreiros enterrados."),
        new City("cape-town", "Cidade do Cabo", "África do Sul", "🇿🇦", "Africa/Johannesburg",
            "Fundada em 1652 no sopé da Table Mountain, abriga a Ilha Robben onde Nelson Mandela permaneceu preso por 18 anos."),
        new City("alexandria", "Alexandria", "Egito", "🇪🇬", "Africa/Cairo",
            "Fundada por Alexandre o Grande em 331 a.C., foi sede do lendário Farol de Alexandria e da mais famosa biblioteca da antiguidade.")
    );

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy (EEEE)");

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> index(@RequestParam(name = "tz", defaultValue = "salvador") String tz) {
        String selectedId = tz.trim().toLowerCase();
        Instant now = Instant.now();

        City active = CITIES.stream()
            .filter(c -> c.id().equals(selectedId))
            .findFirst()
            .orElse(CITIES.getFirst());

        ZoneId activeZone;
        try {
            activeZone = ZoneId.of(active.timezone());
        } catch (Exception e) {
            activeZone = ZoneId.of("UTC");
        }
        ZonedDateTime activeZdt = now.atZone(activeZone);

        StringBuilder navHtml = new StringBuilder();
        for (City c : CITIES) {
            boolean isCur = c.id().equals(active.id());
            String activeClass = isCur ? "active" : "";
            navHtml.append("<a href=\"/?tz=").append(c.id())
                   .append("\" class=\"city-btn ").append(activeClass).append("\">")
                   .append(c.flag()).append(" ").append(c.name()).append("</a>\n");
        }

        String template = loadTemplate();
        long coldStartMs = Math.max(1, System.currentTimeMillis() - DemoApplication.BOOT_TIME_MS);
        String hostname = getHost();

        String html = template
            .replace("{{.Title}}", "Java Enterprise TZ Explorer")
            .replace("{{.AccentColor}}", "#E76F00")
            .replace("{{.ActiveCity.Flag}}", active.flag())
            .replace("{{.ActiveCity.Name}}", active.name())
            .replace("{{.ActiveCity.Country}}", active.country())
            .replace("{{.ActiveCity.Timezone}}", active.timezone())
            .replace("{{.ActiveCity.CurrentTime}}", activeZdt.format(TIME_FMT))
            .replace("{{.ActiveCity.CurrentDate}}", activeZdt.format(DATE_FMT))
            .replace("{{.ActiveCity.Offset}}", activeZdt.getZone().getId() + " " + activeZdt.getOffset().getId())
            .replace("{{.ActiveCity.HistoricalFact}}", active.fact())
            .replace("{{.RuntimeInfo}}", "Java 25 LTS (Spring Boot 3.4+)")
            .replace("{{.ColdStartMs}}", String.valueOf(coldStartMs))
            .replace("{{.Hostname}}", hostname);

        // Substitui a seção inteira do bloco <nav class="city-nav">...</nav>
        String startNav = "<nav class=\"city-nav\">";
        String endNav = "</nav>";
        if (html.contains(startNav) && html.contains(endNav)) {
            int idxStart = html.indexOf(startNav) + startNav.length();
            int idxEnd = html.indexOf(endNav);
            html = html.substring(0, idxStart) + "\n      " + navHtml + "    " + html.substring(idxEnd);
        }

        return ResponseEntity.ok(html);
    }

    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> healthz() {
        return ResponseEntity.ok("OK");
    }

    private String loadTemplate() {
        try {
            ClassPathResource res = new ClassPathResource("template.html");
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "<html><body><h1>Erro ao carregar template.html</h1></body></html>";
        }
    }

    private String getHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "container-pod";
        }
    }
}
