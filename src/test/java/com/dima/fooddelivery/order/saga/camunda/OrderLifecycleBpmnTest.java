package com.dima.fooddelivery.order.saga.camunda;

import com.dima.fooddelivery.order.domain.OrderStatus;
import io.camunda.client.annotation.JobWorker;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.model.bpmn.instance.BoundaryEvent;
import io.camunda.zeebe.model.bpmn.instance.EventBasedGateway;
import io.camunda.zeebe.model.bpmn.instance.FlowNode;
import io.camunda.zeebe.model.bpmn.instance.Message;
import io.camunda.zeebe.model.bpmn.instance.Process;
import io.camunda.zeebe.model.bpmn.instance.ReceiveTask;
import io.camunda.zeebe.model.bpmn.instance.ServiceTask;
import io.camunda.zeebe.model.bpmn.instance.zeebe.ZeebeTaskDefinition;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Модель процесса против кода: имя процесса, сообщения и типы заданий в BPMN должны совпадать
 * с тем, что публикует {@link OrderLifecycleProcess} и обслуживает {@link OrderLifecycleWorkers}.
 * Разойдись они — процесс задеплоится, но задания будут висеть без обработчика.
 */
class OrderLifecycleBpmnTest {

    private final BpmnModelInstance model = read();

    @Test
    void processIdShouldMatchCode() {
        Process process = model.getModelElementById(OrderLifecycleProcess.PROCESS_ID);

        assertEquals(Boolean.TRUE, process != null && process.isExecutable());
    }

    @Test
    void serviceTasksShouldHaveWorkers() {
        Set<String> inModel = model.getModelElementsByType(ServiceTask.class).stream()
                .map(task -> task.getSingleExtensionElement(ZeebeTaskDefinition.class).getType())
                .collect(Collectors.toSet());

        Set<String> inCode = Arrays.stream(OrderLifecycleWorkers.class.getDeclaredMethods())
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

        Set<String> inCode = new java.util.HashSet<>(OrderLifecycleProcess.STATUS_MESSAGES.values());
        inCode.add(OrderLifecycleProcess.MESSAGE_SUCCEEDED);
        inCode.add(OrderLifecycleProcess.MESSAGE_FAILED);

        assertAll(
                () -> assertEquals(inCode, messages),
                // Четыре исхода оплаты ждутся одновременно: кто первый пришёл, тот и решает.
                () -> assertEquals(4, model.getModelElementsByType(EventBasedGateway.class).iterator().next()
                        .getOutgoing().size())
        );
    }

    /** Шаги «Выполнения заказа» идут в том же порядке, что и статусы заказа. */
    @Test
    void fulfillmentStepsShouldFollowOrderStatuses() {
        List<String> expected = List.of(
                OrderLifecycleProcess.STATUS_MESSAGES.get(OrderStatus.ACCEPTED),
                OrderLifecycleProcess.STATUS_MESSAGES.get(OrderStatus.COOKING),
                OrderLifecycleProcess.STATUS_MESSAGES.get(OrderStatus.READY_FOR_DELIVERY),
                OrderLifecycleProcess.STATUS_MESSAGES.get(OrderStatus.IN_DELIVERY),
                OrderLifecycleProcess.STATUS_MESSAGES.get(OrderStatus.DELIVERED)
        );

        List<String> steps = new ArrayList<>();
        FlowNode node = model.getModelElementById("await-accepted");
        while (node instanceof ReceiveTask task) {
            steps.add(task.getMessage().getName());
            node = task.getOutgoing().iterator().next().getTarget();
        }

        assertEquals(expected, steps);
    }

    /** Отмена оплаченного заказа прерывает «Выполнение заказа», а SLA принятия — нет. */
    @Test
    void cancelShouldInterruptFulfillmentButSlaShouldNot() {
        BoundaryEvent cancel = model.getModelElementById("canceled-after-payment");
        BoundaryEvent sla = model.getModelElementById("acceptance-sla");

        assertAll(
                () -> assertEquals("fulfillment", cancel.getAttachedTo().getId()),
                () -> assertTrue(cancel.cancelActivity()),
                () -> assertEquals("await-accepted", sla.getAttachedTo().getId()),
                () -> assertFalse(sla.cancelActivity())
        );
    }

    private static BpmnModelInstance read() {
        try (InputStream in = OrderLifecycleBpmnTest.class.getResourceAsStream("/bpmn/order-lifecycle.bpmn")) {
            BpmnModelInstance instance = Bpmn.readModelFromStream(in);
            Bpmn.validateModel(instance);
            return instance;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
