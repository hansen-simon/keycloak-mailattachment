package org.keycloak.email;

import com.sun.mail.smtp.SMTPMessage;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;
import org.keycloak.theme.Theme;
import org.keycloak.truststore.HostnameVerificationPolicy;
import org.keycloak.truststore.JSSETruststoreConfigurator;

import javax.activation.DataHandler;
import javax.mail.*;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import javax.net.ssl.SSLSocketFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

@JBossLog
public class EmailWithAttachmentSenderProvider implements EmailSenderProvider {
    private final KeycloakSession session;

    public EmailWithAttachmentSenderProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void send(Map<String, String> config, UserModel user, String subject, String textBody, String htmlBody)
            throws EmailException {
        Transport transport = null;
        try {
            String address = retrieveEmailAddress(user);

            Properties props = new Properties();

            if (config.containsKey("host")) {
                props.setProperty("mail.smtp.host", config.get("host"));
            }

            boolean auth = "true".equals(config.get("auth"));
            boolean ssl = "true".equals(config.get("ssl"));
            boolean starttls = "true".equals(config.get("starttls"));

            if (config.containsKey("port") && config.get("port") != null) {
                props.setProperty("mail.smtp.port", config.get("port"));
            }

            if (auth) {
                props.setProperty("mail.smtp.auth", "true");
            }

            if (ssl) {
                props.setProperty("mail.smtp.ssl.enable", "true");
            }

            if (starttls) {
                props.setProperty("mail.smtp.starttls.enable", "true");
            }

            if (ssl || starttls) {
                setupTruststore(props);
            }

            props.setProperty("mail.smtp.timeout", "10000");
            props.setProperty("mail.smtp.connectiontimeout", "10000");

            String from = config.get("from");
            String fromDisplayName = config.get("fromDisplayName");
            String replyTo = config.get("replyTo");
            String replyToDisplayName = config.get("replyToDisplayName");
            String envelopeFrom = config.get("envelopeFrom");

            Session session = Session.getInstance(props);

            Multipart multipart = new MimeMultipart("mixed");
            Multipart innerMultipart = new MimeMultipart("alternative");

            if (textBody != null) {
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(textBody, "UTF-8");
                innerMultipart.addBodyPart(textPart);
            }

            if (htmlBody != null) {
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
                innerMultipart.addBodyPart(htmlPart);
            }

            MimeBodyPart innerMultiPartBody = new MimeBodyPart();
            innerMultiPartBody.setContent(innerMultipart);
            multipart.addBodyPart(innerMultiPartBody);

            Theme theme = this.session.theme().getTheme(Theme.Type.EMAIL);
            addAttachments(multipart, theme);

            SMTPMessage msg = new SMTPMessage(session);
            msg.setFrom(toInternetAddress(from, fromDisplayName));

            msg.setReplyTo(new Address[]{toInternetAddress(from, fromDisplayName)});
            if (replyTo != null && !replyTo.isEmpty()) {
                msg.setReplyTo(new Address[]{toInternetAddress(replyTo, replyToDisplayName)});
            }
            if (envelopeFrom != null && !envelopeFrom.isEmpty()) {
                msg.setEnvelopeFrom(envelopeFrom);
            }

            msg.setHeader("To", address);
            msg.setSubject(subject, "utf-8");
            msg.setContent(multipart);
            msg.saveChanges();
            msg.setSentDate(new Date());

            transport = session.getTransport("smtp");
            if (auth) {
                transport.connect(config.get("user"), config.get("password"));
            } else {
                transport.connect();
            }
            transport.sendMessage(msg, new InternetAddress[]{new InternetAddress(address)});
        } catch (Exception e) {
            ServicesLogger.LOGGER.failedToSendEmail(e);
            throw new EmailException(e);
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException e) {
                    log.warn("Failed to close transport", e);
                }
            }
        }
    }

    protected InternetAddress toInternetAddress(String email, String displayName)
            throws UnsupportedEncodingException, AddressException, EmailException {
        if (email == null || "".equals(email.trim())) {
            throw new EmailException("Please provide a valid address", null);
        }
        if (displayName == null || "".equals(displayName.trim())) {
            return new InternetAddress(email);
        }
        return new InternetAddress(email, displayName, "utf-8");
    }

    protected String retrieveEmailAddress(UserModel user) {
        return user.getEmail();
    }

    private void setupTruststore(Properties props) throws NoSuchAlgorithmException, KeyManagementException {

        JSSETruststoreConfigurator configurator = new JSSETruststoreConfigurator(session);

        SSLSocketFactory factory = configurator.getSSLSocketFactory();
        if (factory != null) {
            props.put("mail.smtp.ssl.socketFactory", factory);
            if (configurator.getProvider().getPolicy() == HostnameVerificationPolicy.ANY) {
                props.setProperty("mail.smtp.ssl.trust", "*");
            }
        }
    }

    @Override
    public void close() {

    }

    private void addAttachment(Multipart multipart, Theme theme, String path) {
        log.debug("addAttachment:" + path);

        // Open stream twice so javax.mailer can get content type
        try {
            String fileName = new File(path).toPath().getFileName().toString();
            log.debug("addAttachment filename:" + fileName + ", path: " + path);

            InputStream is1 = theme.getResourceAsStream(path);
            InputStream is2 = theme.getResourceAsStream(path);

            if (is1 == null || is2 == null) {
                log.warn("Attach stream is null: " + path);
            } else {
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setDataHandler(new DataHandler(new InputStreamDataSource(is1, is2, fileName)));

                htmlPart.setFileName(fileName);
                htmlPart.setDisposition(MimeBodyPart.ATTACHMENT);
                multipart.addBodyPart(htmlPart);
            }
        } catch (MessagingException | IOException e) {
            log.warn("Failed to attach file: " + path, e);
        }
    }

    private void addAttachments(Multipart multipart, Theme theme) {
        try {
            Properties properties = theme.getProperties();
            String rawAttachments = properties.getProperty("attachments");
            if (rawAttachments == null) {
                log.warn("Property attachments not found in theme");
                return;
            }
            String[] attachments = rawAttachments.split(",");
            for (String attachment : attachments) {
                addAttachment(multipart, theme, attachment);
            }
        } catch (IOException e) {
            log.warn("Failed to get theme properties", e);
        }
    }
}
