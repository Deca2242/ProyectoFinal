package com.web.service.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.web.entity.Notification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

// El envío simulado solo deja el mensaje en el log con el prefijo del canal
class LogNotificationSenderTest {

    private final LogNotificationSender sender = new LogNotificationSender();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LogNotificationSender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void shouldSend_WhatsApp_LogWithMockWhatsappPrefix() {
        // When
        sender.send(Notification.Channel.WHATSAPP, "3001234567", "Compra confirmada");

        // Then
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("[MOCK WHATSAPP] Para 3001234567: Compra confirmada");
        });
    }

    @Test
    void shouldSend_Sms_LogWithMockSmsPrefix() {
        // When
        sender.send(Notification.Channel.SMS, "6011234567", "Cambio de andén");

        // Then
        assertThat(appender.list).singleElement()
                .extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("[MOCK SMS] Para 6011234567: Cambio de andén");
    }
}
