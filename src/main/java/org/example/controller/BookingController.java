package org.example.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.command.CreateProcessInstanceCommandStep1;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import lombok.RequiredArgsConstructor;
import org.example.entity.CarRequest;
import org.example.repository.CarRequestRepository;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/booking")
@RequiredArgsConstructor
public class BookingController {

    private final ZeebeClient zeebeClient;
    private final CarRequestRepository carRequestRepository;
    private final RestTemplate restTemplate = new RestTemplate(); // dùng để gọi HTTP API

    @PostMapping("/request")
    public ResponseEntity<Long> createRequest(@RequestHeader("Authorization") String authorizationHeader,@RequestBody Map<String, String> payload) {
        CarRequest carRequest = new CarRequest();
        String accessToken = authorizationHeader.substring(7); // Remove "Bearer "
        // ✅ 1. Giải mã JWT để lấy groups
        DecodedJWT jwt = JWT.decode(accessToken);
        List<String> userGroups = jwt.getClaim("groups").asList(String.class);
        String userName = jwt.getClaim("preferred_username").asString();
        carRequest.setCarName(payload.get("carName"));
        carRequest.setRole(payload.get(userGroups.get(0)));
        carRequest.setPurpose(payload.get("otherInfo"));
        carRequest.setUsername(payload.get(userName));
        carRequest.setStartDate(payload.get("startDate"));
        carRequest.setEndDate(payload.get("endDate"));
        CarRequest carRequest1 = carRequestRepository.save(carRequest);
        payload.put("requestId", carRequest1.getId().toString());
        payload.put("requester", userName);
        payload.put("role", userGroups.get(0));
        ProcessInstanceEvent result = zeebeClient.newCreateInstanceCommand()
                .bpmnProcessId("book_approval")
                .latestVersion()
                .variables(payload)
                .send()
                .join();
        carRequest1.setProcessInstanceKey(result.getProcessInstanceKey());
        carRequest1.setBpmnProcessId(result.getBpmnProcessId());
        carRequest1.setVersion(result.getVersion());
        carRequest1.setProcessDefinitionKey(result.getProcessDefinitionKey());
        carRequestRepository.save(carRequest1);
        return ResponseEntity.ok(result.getProcessInstanceKey());
    }

    @PostMapping("/complete-task")
    public ResponseEntity<String> completeTask(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Map<String, String> request) {

        String taskId = request.get("taskId");
        String approve = request.get("approve");

        if (taskId == null || authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body("taskId, completedBy, and Bearer token are required");
        }

        String accessToken = authorizationHeader.substring(7); // Remove "Bearer "

        // ✅ 1. Giải mã JWT để lấy groups
        DecodedJWT jwt = JWT.decode(accessToken);
        List<String> userGroups = jwt.getClaim("groups").asList(String.class);

        // ✅ 2. Gọi API Tasklist để lấy candidateGroups
        String taskInfoUrl = "http://localhost:8082/v1/tasks/" + taskId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> taskResponse;
        try {
            taskResponse = restTemplate.exchange(taskInfoUrl, HttpMethod.GET, entity, Map.class);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Không tìm thấy task hoặc token không hợp lệ");
        }

        Map<String, Object> taskBody = taskResponse.getBody();
        List<String> candidateGroups = (List<String>) taskBody.get("candidateGroups");

        // ✅ 3. Kiểm tra quyền
        boolean isAllowed = userGroups != null && candidateGroups != null &&
                userGroups.stream().anyMatch(candidateGroups::contains);

        if (!isAllowed) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Bạn không có quyền complete task này.");
        }

        // ✅ 4. Nếu hợp lệ, gọi API completion
        String url = "http://localhost:8088/v2/user-tasks/" + taskId + "/completion";

        Map<String, Object> payload = new HashMap<>();
        Map<String, Object> variables = new HashMap<>();
        variables.put("completedBy", userGroups.get(0));
        variables.put("approve", approve);
        payload.put("variables", variables);

        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> completeEntity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, completeEntity, String.class);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to complete task: " + e.getMessage());
        }
    }


}
