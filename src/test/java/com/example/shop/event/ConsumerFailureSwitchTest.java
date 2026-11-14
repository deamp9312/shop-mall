package com.example.shop.event;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REQ-F-014 3): the switch fails the next N events on every delivery attempt, then lets their redelivery pass.
 *
 * REQ-F-014 3): 스위치는 다음 N개의 이벤트를 매 전달 시도마다 실패시키고, 이후 재전달은 통과시킨다.
 */
class ConsumerFailureSwitchTest {

    @Test
    void armedEventFailsEveryAttemptAndItsLaterDeliveryPasses() {
        ConsumerFailureSwitch failureSwitch = new ConsumerFailureSwitch(true);
        failureSwitch.arm(ConsumerGroups.STOCK, 1);

        for (int attempt = 0; attempt < KafkaConfig.DELIVERY_ATTEMPTS; attempt++) {
            assertThatThrownBy(() -> failureSwitch.failIfArmed(ConsumerGroups.STOCK, "event-a"))
                    .isInstanceOf(ConsumerFailureSwitch.ForcedConsumerFailure.class);
        }

        assertThatCode(() -> failureSwitch.failIfArmed(ConsumerGroups.STOCK, "event-a")).doesNotThrowAnyException();
        assertThatCode(() -> failureSwitch.failIfArmed(ConsumerGroups.STOCK, "event-b")).doesNotThrowAnyException();
        assertThat(failureSwitch.remainingFailures()).isEmpty();
    }

    @Test
    void otherGroupsAreNotAffected() {
        ConsumerFailureSwitch failureSwitch = new ConsumerFailureSwitch(true);
        failureSwitch.arm(ConsumerGroups.STOCK, 2);

        assertThatCode(() -> failureSwitch.failIfArmed(ConsumerGroups.ORDER, "event-a")).doesNotThrowAnyException();
        assertThat(failureSwitch.remainingFailures()).containsEntry(ConsumerGroups.STOCK, 2);
    }

    @Test
    void disabledSwitchCannotBeArmedAndNeverFails() {
        ConsumerFailureSwitch failureSwitch = new ConsumerFailureSwitch(false);

        assertThatThrownBy(() -> failureSwitch.arm(ConsumerGroups.STOCK, 1))
                .isInstanceOf(ShopException.class)
                .extracting(e -> ((ShopException) e).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatCode(() -> failureSwitch.failIfArmed(ConsumerGroups.STOCK, "event-a")).doesNotThrowAnyException();
    }
}
