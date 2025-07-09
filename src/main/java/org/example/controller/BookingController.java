package org.example.controller;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    @PostMapping("/flow-nodes")
    public ResponseEntity<Object> getFlowNodeInstances(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Map<String, String> request
    ) {
        String processInstanceId = request.get("processInstanceId");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ") || processInstanceId == null) {
            return ResponseEntity.badRequest().body("Authorization header and processInstanceId are required");
        }

        String accessToken = authorizationHeader.substring(7);

        String url = "http://localhost:8081/api/flow-node-instances";

        // Payload gửi sang Tasklist API
        Map<String, Object> query = new HashMap<>();
        query.put("processInstanceId", processInstanceId);
        query.put("treePath", processInstanceId);
        query.put("pageSize", 50);

        Map<String, Object> payload = new HashMap<>();
        payload.put("queries", List.of(query));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> body = response.getBody();
                if (body == null || body.isEmpty()) {
                    return ResponseEntity.ok(Collections.emptyList());
                }

                // Lấy phần tử đầu tiên (theo ID process instance dynamic)
                Map.Entry<String, Object> entry = body.entrySet().iterator().next();
                Map<String, Object> value = (Map<String, Object>) entry.getValue();
                List<Map<String, Object>> children = (List<Map<String, Object>>) value.get("children");

                // Load BPMN để map flowNodeId -> name
                InputStream bpmnXml = getClass().getClassLoader().getResourceAsStream("car_book.bpmn");
                Map<String, String> idToNameMap = extractFlowNodeNames(bpmnXml);

                // Thêm flowNodeName cho mỗi phần tử children
                List<Map<String, Object>> enriched = children.stream().map(child -> {
                    String flowNodeId = (String) child.get("flowNodeId");
                    String nodeName = idToNameMap.getOrDefault(flowNodeId, "");
                    child.put("flowNodeName", nodeName);
                    return child;
                }).collect(Collectors.toList());

                return ResponseEntity.ok(enriched);
            } else {
                return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch flow-node-instances: " + e.getMessage());
        }
    }


    public Map<String, String> extractFlowNodeNames(InputStream bpmnXmlInputStream) throws Exception {
        Map<String, String> nodeNames = new HashMap<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(bpmnXmlInputStream);

        NodeList userTasks = doc.getElementsByTagNameNS("*", "userTask");
        NodeList serviceTasks = doc.getElementsByTagNameNS("*", "serviceTask");
        NodeList gateways = doc.getElementsByTagNameNS("*", "exclusiveGateway");
        NodeList start = doc.getElementsByTagNameNS("*", "startEvent");
        NodeList end = doc.getElementsByTagNameNS("*", "endEvent");

        extractNodeInfo(userTasks, nodeNames);
        extractNodeInfo(serviceTasks, nodeNames);
        extractNodeInfo(gateways, nodeNames);
        extractNodeInfo(start, nodeNames);
        extractNodeInfo(end, nodeNames);

        return nodeNames;
    }

    private void extractNodeInfo(NodeList nodeList, Map<String, String> map) {
        for (int i = 0; i < nodeList.getLength(); i++) {
            Element element = (Element) nodeList.item(i);
            String id = element.getAttribute("id");
            String name = element.getAttribute("name");
            if (id != null && !id.isEmpty()) {
                map.put(id, name != null ? name : id);
            }
        }
    }
    @PostMapping("/get-tasks")
    public ResponseEntity<Object> getTasks(
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Map<String, Object> requestBody
    ) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body("Authorization header (Bearer token) is required");
        }
        String accessToken = authorizationHeader.substring(7); // Bỏ "Bearer "

        DecodedJWT jwt = JWT.decode(accessToken);
        List<String> userGroups = jwt.getClaim("groups").asList(String.class);
        requestBody.put("candidateGroup", userGroups.get(0));
        String url = "http://localhost:8082/v1/tasks/search";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            // Nhận về chuỗi JSON
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            // Nếu muốn parse chuỗi JSON thành Map (hoặc trả nguyên chuỗi luôn)
            ObjectMapper objectMapper = new ObjectMapper();
            Object json = objectMapper.readValue(response.getBody(), Object.class);

            return ResponseEntity.status(response.getStatusCode()).body(json);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to call Task Search API: " + e.getMessage());
        }
    }




}
