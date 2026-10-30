package com.dima.fooddelivery.order.saga.camunda;

import io.camunda.client.annotation.JobWorker;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.EventBasedGateway;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Модель процесса против кода: имя процесса, сообщения и типы заданий в BPMN должны совпадать
 * с тем, что публикует {@link PaymentProcess} и обслуживает {@link PaymentProcessWorkers}.
 * Разойдись они — процесс задеплоится, но задания будут висеть без обработчика.
 */
class PaymentSagaBpmnTest {

    private final BpmnModelInstance model = read();

    @Test
    void processIdShouldMatchCode() {
        Process process = model.getModelElementById(PaymentProcess.PROCESS_ID);

        assertEquals(Boolean.TRUE, process != null && process.isExecutable());
    }

    @Test
    void serviceTasksShouldHaveWorkers() {
        Set<String> inModel = model.getModelElementsByType(ServiceTask.class).stream()
                .map(task -> task.getSingleExtensionElement(ZeebeTaskDefinition.class).getType())
                .collect(Collectors.toSet());

        Set<String> inCode = Arrays.stream(PaymentProcessWorkers.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(JobWorker.class))
                .map(method -> method.getAnnotation(JobWorker.class).type())
                .collect(Collectors.toSet());

        assertEquals(inCode, inModel);
    }

    @Test
    void messagesShouldMatchCode() {
        Set<String> messages = model.getModelElementsByType(Message.class).stream()
                .map(Message::getName)
                .collect(Collectors.toSet());

        assertAll(
                () -> assertEquals(Set.of(PaymentProcess.MESSAGE_SUCCEEDED, PaymentProcess.MESSAGE_FAILED,
                        PaymentProcess.MESSAGE_ORDER_CANCELED), messages),
                // Четыре исхода ждутся одновременно: кто первый пришёл, тот и решает.
                () -> assertEquals(4, model.getModelElementsByType(EventBasedGateway.class).iterator().next()
                        .getOutgoing().size())
        );
    }

    private static BpmnModelInstance read() {
        try (InputStream in = PaymentSagaBpmnTest.class.getResourceAsStream("/bpmn/payment-saga.bpmn")) {
            BpmnModelInstance instance = Bpmn.readModelFromStream(in);
            Bpmn.validateModel(instance);
            return instance;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
