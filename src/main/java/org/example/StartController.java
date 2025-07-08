package org.example;

import io.camunda.zeebe.client.ZeebeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StartController {

    private final ZeebeClient zeebeClient;

    @PostMapping("/start")
    public String startProcess(@RequestBody Map<String, Object> vars) {
        zeebeClient.newCreateInstanceCommand()
                .bpmnProcessId("car_booking")
                .latestVersion()
                .variables(vars)
                .send()
                .join();

        return "🚀 car_booking process started!";
    }
}

