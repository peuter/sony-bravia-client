package com.jamierf.sony.bravia.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.MediaType;

import org.apache.commons.codec.DecoderException;
import org.bitlet.weupnp.NameValueHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import com.jamierf.sony.bravia.client.api.Request;
import com.jamierf.sony.bravia.client.api.Result;
import com.jamierf.sony.bravia.client.error.CommunicationException;
import com.jamierf.sony.bravia.client.model.Command;
import com.jamierf.wol.WakeOnLan;
import com.sun.jersey.api.client.Client;

// http://mendel129.wordpress.com/2014/01/21/more-sony-bravia-stuff/
// http://mendelonline.be/sony/sony.txt
// http://forum.serviio.org/viewtopic.php?f=4&t=12534
// http://192.168.0.104:52323/dmr.xml
public class BraviaClient {

    private static final String API_PATH = "/sony/%s";

    private static final String IRCC_SERVICE_ID = "urn:schemas-sony-com:service:IRCC:1";
    private static final String IRCC_SEND_ACTION = "X_SendIRCC";
    private static final String IRCC_PATH = "/sony/IRCC";
    private static final String IRCC_ARG_KEY = "IRCCCode";

    /**
     * Receive timeout when requesting data from device
     */
    private static final int HTTP_RECEIVE_TIMEOUT = 7000;

    private static final Logger LOG = LoggerFactory.getLogger(BraviaClient.class);

    private final Client client;
    private final URI root;
    private final String macAddress;
    private final String authentification;

    public BraviaClient(final Client client, final URI root, final String macAddress, final String auth) {
        this.client = client;
        this.root = root;
        this.macAddress = macAddress;
        this.authentification = auth;
    }

    @SuppressWarnings("unchecked")
    private <T, U extends Result> T query(final String service, final String method, final Class<U> type) {
        final String path = String.format(API_PATH, service);
        final Result<T> result = client.resource(root).path(path).type(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).post(type, new Request(method));

        return result.getResult();
    }

    public void turnOn() {
        LOG.trace("Turning on {}", macAddress);
        try {
            WakeOnLan.wake(macAddress);
        } catch (IOException | DecoderException e) {
            throw new CommunicationException("Error turning on " + macAddress, e);
        }
    }

    public void turnOff() {
        sendCommand(Command.POWER_OFF);
    }

    public void sendCommand(final Command code) {
        sendCommand(code.getCode());
    }

    private void sendCommand(final String code) {
        final String url = String.format("http://%s%s", root.getHost(), IRCC_PATH);

        try {
            LOG.trace("Sending IRCC: {}", code);

            this.sendUPnPcommand(url, code);
        } catch (IOException | SAXException e) {
            throw new CommunicationException("Error sending code " + code, e);
        }
    }

    private Map<String, String> sendUPnPcommand(String url, String code) throws IOException, SAXException {
        StringBuilder soapBody = new StringBuilder();

        soapBody.append("<?xml version=\"1.0\"?>\r\n" + "<SOAP-ENV:Envelope "
                + "xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "SOAP-ENV:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" + "<SOAP-ENV:Body>" + "<m:"
                + IRCC_SEND_ACTION + " xmlns:m=\"" + IRCC_SERVICE_ID + "\">" + "<" + IRCC_ARG_KEY + ">" + code + "</"
                + IRCC_ARG_KEY + ">" + "</m:" + IRCC_SEND_ACTION + ">" + "</SOAP-ENV:Body></SOAP-ENV:Envelope>");

        URL postUrl = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) postUrl.openConnection();

        conn.setRequestMethod("POST");
        conn.setReadTimeout(HTTP_RECEIVE_TIMEOUT);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/xml");
        conn.setRequestProperty("SOAPAction", "\"" + IRCC_SERVICE_ID + "#" + IRCC_SEND_ACTION + "\"");
        conn.setRequestProperty("Cookie", "auth=" + this.authentification);
        conn.setRequestProperty("Connection", "Close");

        byte[] soapBodyBytes = soapBody.toString().getBytes();

        conn.setRequestProperty("Content-Length", String.valueOf(soapBodyBytes.length));

        conn.getOutputStream().write(soapBodyBytes);

        Map<String, String> nameValue = new HashMap<String, String>();
        XMLReader parser = XMLReaderFactory.createXMLReader();
        parser.setContentHandler(new NameValueHandler(nameValue));
        if (conn.getResponseCode() == HttpURLConnection.HTTP_INTERNAL_ERROR) {
            try {
                parser.parse(new InputSource(conn.getErrorStream()));
            } catch (SAXException e) {
            }
            conn.disconnect();
            return nameValue;
        } else {
            parser.parse(new InputSource(conn.getInputStream()));
            conn.disconnect();
            return nameValue;
        }
    }
}
