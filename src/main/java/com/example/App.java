package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@SpringBootApplication
public class App implements CommandLineRunner {

  public static void main(String[] args) {
    SpringApplication.run(App.class, args);
  }

  @Override
  public void run(String... args) throws Exception {
    var ctx = getApplicationContext(this);
    var env = ctx.getEnvironment();

    String name  = env.getProperty("app.name", "Your Name");
    String regNo = env.getProperty("app.regNo", "YOURREGNO1234");
    String email = env.getProperty("app.email", "you@example.com");

    System.out.println("Starting with name=" + name + ", regNo=" + regNo + ", email=" + email);

    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    ObjectMapper om = new ObjectMapper();

    // 1) Call generateWebhook
    String genBody = String.format("{\"name\":\"%s\",\"regNo\":\"%s\",\"email\":\"%s\"}", esc(name), esc(regNo), esc(email));
    HttpRequest genReq = HttpRequest.newBuilder()
        .uri(URI.create("https://bfhldevapigw.healthrx.co.in/hiring/generateWebhook/JAVA"))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(genBody, StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> genRes = client.send(genReq, HttpResponse.BodyHandlers.ofString());
    System.out.println("generateWebhook status: " + genRes.statusCode());
    System.out.println("generateWebhook body: " + genRes.body());

    if (genRes.statusCode() / 100 != 2) {
      System.out.println("Failed to generate webhook. Exiting.");
      return;
    }

    JsonNode genJson = om.readTree(genRes.body());
    String accessToken = genJson.path("accessToken").asText();
    String webhook     = genJson.path("webhook").asText();
    System.out.println("accessToken=" + accessToken);
    System.out.println("webhook=" + webhook);

    if (accessToken == null || accessToken.isBlank()) {
      System.out.println("No accessToken received. Exiting.");
      return;
    }

    // 2) Decide which SQL to submit (odd -> Q1 placeholder, even -> Q2 actual)
    boolean isOdd = lastTwoDigitsOdd(regNo);
    String finalQuery;
    if (isOdd) {
      finalQuery = "/* TODO: Write SQL for Q1 (odd last-two digits) */\nSELECT 1;";
    } else {
      finalQuery =
          "SELECT\n" +
          "  e.EMP_ID,\n" +
          "  e.FIRST_NAME,\n" +
          "  e.LAST_NAME,\n" +
          "  d.DEPARTMENT_NAME,\n" +
          "  (\n" +
          "    SELECT COUNT(1)\n" +
          "    FROM EMPLOYEE e2\n" +
          "    WHERE e2.DEPARTMENT = e.DEPARTMENT\n" +
          "      AND e2.DOB > e.DOB\n" +
          "  ) AS YOUNGER_EMPLOYEES_COUNT\n" +
          "FROM EMPLOYEE e\n" +
          "JOIN DEPARTMENT d ON d.DEPARTMENT_ID = e.DEPARTMENT\n" +
          "ORDER BY e.EMP_ID DESC";
    }

    String submitBody = "{\"finalQuery\":" + om.writeValueAsString(finalQuery) + "}";

    HttpRequest subReq = HttpRequest.newBuilder()
        .uri(URI.create("https://bfhldevapigw.healthrx.co.in/hiring/testWebhook/JAVA"))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json")
        .header("Authorization", accessToken) // **raw token**, not "Bearer ..."
        .POST(HttpRequest.BodyPublishers.ofString(submitBody, StandardCharsets.UTF_8))
        .build();

    HttpResponse<String> subRes = client.send(subReq, HttpResponse.BodyHandlers.ofString());
    System.out.println("submit status: " + subRes.statusCode());
    System.out.println("submit body: " + subRes.body());

    if (subRes.statusCode() / 100 == 2) {
      System.out.println(" Done. Server accepted the query.");
    } else {
      System.out.println(" Server did not accept the query. Check logs/body.");
    }
  }

  private static org.springframework.context.ApplicationContext getApplicationContext(App app) {
    try {
      var fld = org.springframework.context.support.ApplicationObjectSupport.class.getDeclaredField("applicationContext");
      fld.setAccessible(true);
      return (org.springframework.context.ApplicationContext) fld.get(app);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean lastTwoDigitsOdd(String regNo) {
    String digits = regNo.replaceAll("\\D+", "");
    if (digits.length() < 2) return false;
    int lastTwo = Integer.parseInt(digits.substring(digits.length()-2));
    return (lastTwo % 2) == 1;
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
