package org.example.config;

import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.stereotype.Component;

@Component
public class AutoApproveWorker {

    private final ZeebeClient zeebeClient;

    public AutoApproveWorker(ZeebeClient zeebeClient) {
        this.zeebeClient = zeebeClient;

        zeebeClient.newWorker()
                .jobType("auto-approve") // phải khớp với camunda:type trong BPMN
                .handler((client, job) -> {
                    System.out.println("✅ Auto approved");
                    client.newCompleteCommand(job.getKey())
                            .send()
                            .join();
                })
                .name("auto-approve-worker")
                .open();
    }
}
