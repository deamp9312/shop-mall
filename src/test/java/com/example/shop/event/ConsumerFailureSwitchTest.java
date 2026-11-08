package com.example.shop.event;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.ShopException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** REQ-F-014 3): the switch fails the next N events on every delivery attempt, then lets their redelivery pass. */
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
