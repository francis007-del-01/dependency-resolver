package com.depresolver.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final String API_BASE = "https://api.github.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String token;

    public GitHubClient(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // --- File operations (Contents API) ---

    public record FileContent(String content, String sha, String path) {}
    public record PullRequest(int number, String url, String headBranch, String baseBranch) {}

    public FileContent getFileContent(String owner, String repo, String path, String ref) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/contents/%s?ref=%s".formatted(API_BASE, owner, repo, path, ref);
        JsonNode node = get(url);

        String encodedContent = node.get("content").asText().replaceAll("\\s", "");
        String content = new String(Base64.getDecoder().decode(encodedContent), StandardCharsets.UTF_8);
        String sha = node.get("sha").asText();

        return new FileContent(content, sha, path);
    }

    public void updateFile(String owner, String repo, String path, String content,
                           String fileSha, String branch, String commitMessage) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/contents/%s".formatted(API_BASE, owner, repo, path);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("message", commitMessage);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("sha", fileSha);
        body.put("branch", branch);

        put(url, body);
        log.info("Updated {} on branch {} in {}/{}", path, branch, owner, repo);
    }

    public String getBranchHeadSha(String owner, String repo, String branch) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/git/ref/heads/%s".formatted(API_BASE, owner, repo, branch);
        JsonNode node = get(url);
        JsonNode sha = node.path("object").path("sha");
        if (sha.isMissingNode() || sha.isNull() || sha.asText().isBlank()) {
            throw new IOException("Missing branch head SHA for %s/%s branch %s".formatted(owner, repo, branch));
        }
        return sha.asText();
    }

    public void createBranch(String owner, String repo, String newBranch, String baseSha)
            throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/git/refs".formatted(API_BASE, owner, repo);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("ref", "refs/heads/" + newBranch);
        body.put("sha", baseSha);
        post(url, body);
        log.info("Created branch {} in {}/{} from {}", newBranch, owner, repo, baseSha);
    }

    public PullRequest createPullRequest(String owner, String repo, String title, String bodyText,
                                         String headBranch, String baseBranch) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/pulls".formatted(API_BASE, owner, repo);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("title", title);
        body.put("head", headBranch);
        body.put("base", baseBranch);
        body.put("body", bodyText);
        JsonNode node = post(url, body);
        int number = node.path("number").asInt(-1);
        String prUrl = node.path("html_url").asText(null);
        return new PullRequest(number, prUrl, headBranch, baseBranch);
    }

    // --- Tag & commit inspection ---

    public record CommitAuthor(String name, String email, String login) {}

    public List<CommitAuthor> compareCommits(String owner, String repo, String base, String head)
            throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/compare/%s...%s".formatted(API_BASE, owner, repo, base, head);
        JsonNode node = get(url);
        List<CommitAuthor> authors = new ArrayList<>();
        JsonNode commits = node.get("commits");
        if (commits != null && commits.isArray()) {
            for (JsonNode commit : commits) {
                String name = null, email = null, login = null;
                JsonNode commitAuthor = commit.path("commit").path("author");
                if (!commitAuthor.isMissingNode()) {
                    JsonNode n = commitAuthor.get("name");
                    JsonNode e = commitAuthor.get("email");
                    if (n != null && !n.isNull()) name = n.asText();
                    if (e != null && !e.isNull()) email = e.asText();
                }
                JsonNode ghAuthor = commit.get("author");
                if (ghAuthor != null && !ghAuthor.isNull()) {
                    JsonNode l = ghAuthor.get("login");
                    if (l != null && !l.isNull()) login = l.asText();
                }
                authors.add(new CommitAuthor(name, email, login));
            }
        }
        return authors;
    }

    // --- HTTP helpers ---

    private JsonNode get(String url) throws IOException, InterruptedException {
        return MAPPER.readTree(sendGet(url).body());
    }

    private HttpResponse<String> sendGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkRateLimit(response);
        checkResponse(response, url);
        return response;
    }

    private JsonNode put(String url, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkRateLimit(response);
        checkResponse(response, url);
        return MAPPER.readTree(response.body());
    }

    private JsonNode post(String url, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkRateLimit(response);
        checkResponse(response, url);
        return MAPPER.readTree(response.body());
    }

    private void checkResponse(HttpResponse<String> response, String url) throws IOException {
        int status = response.statusCode();
        if (status == 409) {
            throw new GitHubConflictException("Conflict updating %s: %s".formatted(url, response.body()));
        }
        if (status >= 400) {
            throw new IOException("GitHub API error %d for %s: %s".formatted(status, url, response.body()));
        }
    }

    private void checkRateLimit(HttpResponse<String> response) {
        String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
        if (remaining != null) {
            int rem = Integer.parseInt(remaining);
            if (rem < 100) {
                log.warn("GitHub API rate limit low: {} remaining", rem);
            }
            if (rem <= 0) {
                String resetEpoch = response.headers().firstValue("X-RateLimit-Reset").orElse("0");
                long resetAt = Long.parseLong(resetEpoch);
                long waitSeconds = resetAt - System.currentTimeMillis() / 1000;
                log.error("GitHub API rate limit exhausted. Resets in {} seconds", waitSeconds);
            }
        }
    }
}
