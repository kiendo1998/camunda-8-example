package org.example;

import io.camunda.zeebe.client.ZeebeClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessDeployer {

    private final ZeebeClient zeebeClient;

    @PostConstruct
    public void deploy() {
        var inputStream = getClass().getClassLoader().getResourceAsStream("car_book.bpmn");

        if (inputStream == null) {
            throw new IllegalStateException("❌ Không tìm thấy file BPMN: car_booking.bpmn trong resources");
        }
        System.out.println("Path: " + Main.class.getClassLoader().getResource("car_book.bpmn"));

        zeebeClient.newDeployResourceCommand()
                .addResourceStream(inputStream, "car_book.bpmn")
                .send()
                .join();

        System.out.println("✅ Đã deploy BPMN thành công.");
    }

}

