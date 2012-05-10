package qpid;

import org.apache.qpid.AMQException;
import org.apache.qpid.client.*;
import org.apache.qpid.jms.Session;
import org.apache.qpid.url.URLSyntaxException;

import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.TextMessage;
import java.util.Enumeration;

/**
 * Created with IntelliJ IDEA.
 * User: tonyg
 * Date: 2012-05-10
 * Time: 10:38 AM
 * To change this template use File | Settings | File Templates.
 */
public class SimpleSender {
    public static void main(String[] args) throws Exception {
        System.setProperty("qpid.amqp.version", "0-91");
        AMQConnection conn = new AMQConnection("localhost", "guest", "guest", "clientId", "");
        Session s = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        AMQDestination q1 = new AMQQueue("", "q1");
        ((AMQSession) s).declareAndBind(q1);
        MessageProducer p = s.createProducer(q1, false, false);
        p.send(s.createTextMessage("hello"));
        Thread.sleep(1000);
    }
}
