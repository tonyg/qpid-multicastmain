//  The contents of this file are subject to the Mozilla Public License
//  Version 1.1 (the "License"); you may not use this file except in
//  compliance with the License. You may obtain a copy of the License
//  at http://www.mozilla.org/MPL/
//
//  Software distributed under the License is distributed on an "AS IS"
//  basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
//  the License for the specific language governing rights and
//  limitations under the License.
//
//  The Original Code is RabbitMQ.
//
//  The Initial Developer of the Original Code is VMware, Inc.
//  Copyright (c) 2007-2012 VMware, Inc.  All rights reserved.
//
//  Hacked into shape to work with the Qpid client APIs by
//  Tony Garnock-Jones, May 2012.
//
package qpid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.qpid.client.*;
import org.apache.qpid.client.message.JMSBytesMessage;
import org.apache.qpid.framing.AMQShortString;

import javax.jms.*;

public class MulticastMain {

    public static void main(String[] args) {
        Options options = getOptions();
        CommandLineParser parser = new GnuParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption('?')) {
                usage(options);
                System.exit(0);
            }

            String exchangeType  = strArg(cmd, 't', "direct");
            String exchangeName  = strArg(cmd, 'e', exchangeType);
            String queueName     = strArg(cmd, 'u', "");
            int samplingInterval = intArg(cmd, 'i', 1);
            int rateLimit        = intArg(cmd, 'r', 0);
            int producerCount    = intArg(cmd, 'x', 1);
            int consumerCount    = intArg(cmd, 'y', 1);
            int producerTxSize   = intArg(cmd, 'm', 0);
            int consumerTxSize   = intArg(cmd, 'n', 0);
            long confirm         = intArg(cmd, 'c', -1);
            boolean autoAck      = cmd.hasOption('a');
            int multiAckEvery    = intArg(cmd, 'A', 0);
            int prefetchCount    = intArg(cmd, 'q', 0);
            int minMsgSize       = intArg(cmd, 's', 0);
            int timeLimit        = intArg(cmd, 'z', 0);
            List<?> flags        = lstArg(cmd, 'f');
            int frameMax         = intArg(cmd, 'M', 0);
            int heartbeat        = intArg(cmd, 'b', 0);
            String uri           = strArg(cmd, 'h', "amqp://guest:guest@/?brokerlist='localhost'");

            boolean exclusive  = "".equals(queueName);
            boolean autoDelete = !exclusive;

            //setup
            String id = UUID.randomUUID().toString();
            Stats stats = new Stats(1000L * samplingInterval,
                    producerCount > 0,
                    consumerCount > 0,
                    (flags.contains("mandatory") ||
                            flags.contains("immediate")),
                    confirm != -1);

            System.setProperty("qpid.amqp.version", "0-91");

            Thread[] consumerThreads = new Thread[consumerCount];
            AMQConnection[] consumerConnections = new AMQConnection[consumerCount];
            for (int i = 0; i < consumerCount; i++) {
                System.out.println("starting consumer #" + i);
                AMQConnection conn = new AMQConnection(uri);
                consumerConnections[i] = conn;
                AMQSession channel = (AMQSession) conn.createSession(
                        consumerTxSize > 0,
                        autoAck ? Session.AUTO_ACKNOWLEDGE : Session.CLIENT_ACKNOWLEDGE,
                        prefetchCount > 0 ? prefetchCount : 500);
                AMQDestination destination = new AMQAnyDestination(
                        new AMQShortString(exchangeName),
                        new AMQShortString(exchangeType),
                        new AMQShortString(id),
                        exclusive,
                        autoDelete,
                        queueName.equals("") ? null : new AMQShortString(queueName),
                        flags.contains("persistent"),
                        null);
                channel.declareAndBind(destination);
                Thread t =
                        new Thread(new Consumer(channel, id, destination,
                                consumerTxSize, autoAck,
                                multiAckEvery, stats, timeLimit));
                conn.start();
                consumerThreads[i] = t;
            }
            Thread[] producerThreads = new Thread[producerCount];
            AMQConnection[] producerConnections = new AMQConnection[producerCount];
            AMQSession[] producerChannels = new AMQSession[producerCount];
            for (int i = 0; i < producerCount; i++) {
                System.out.println("starting producer #" + i);
                AMQConnection conn = new AMQConnection(uri);
                producerConnections[i] = conn;
                AMQSession channel = (AMQSession) conn.createSession(
                        producerTxSize > 0,
                        Session.AUTO_ACKNOWLEDGE,
                        500);
                producerChannels[i] = channel;
                if (confirm >= 0) {
                    throw new UnsupportedOperationException("Publisher confirms not supported by Qpid");
                }
                AMQDestination destination = new AMQAnyDestination(
                        new AMQShortString(exchangeName),
                        new AMQShortString(exchangeType),
                        new AMQShortString(id),
                        false,
                        false,
                        null,
                        flags.contains("persistent"),
                        null);
                channel.declareExchange(destination.getExchangeName(), destination.getExchangeClass(), false);
                final Producer p = new Producer(channel, destination, id,
                        flags, producerTxSize,
                        rateLimit, minMsgSize, timeLimit,
                        confirm, stats);
                /*
                channel.addReturnListener(p);
                channel.addConfirmListener(p);
                */
                Thread t = new Thread(p);
                conn.start();
                producerThreads[i] = t;
            }

            for (int i = 0; i < consumerCount; i++) {
                consumerThreads[i].start();
            }

            for (int i = 0; i < producerCount; i++) {
                producerThreads[i].start();
            }

            for (int i = 0; i < producerCount; i++) {
                producerThreads[i].join();
                /*
                producerChannels[i].clearReturnListeners();
                producerChannels[i].clearConfirmListeners();
                */
                producerConnections[i].close();
            }

            for (int i = 0; i < consumerCount; i++) {
                consumerThreads[i].join();
                consumerConnections[i].close();
            }

        }
        catch( ParseException exp ) {
            System.err.println("Parsing failed. Reason: " + exp.getMessage());
            usage(options);
        } catch (Exception e) {
            System.err.println("Main thread caught exception: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void usage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("<program>", options);
    }

    private static Options getOptions() {
        Options options = new Options();
        options.addOption(new Option("?", "help",          false,"show usage"));
        options.addOption(new Option("h", "uri",           true, "AMQP URI"));
        options.addOption(new Option("t", "type",          true, "exchange type"));
        options.addOption(new Option("e", "exchange",      true, "exchange name"));
        options.addOption(new Option("u", "queue",         true, "queue name"));
        options.addOption(new Option("i", "interval",      true, "sampling interval"));
        options.addOption(new Option("r", "rate",          true, "rate limit"));
        options.addOption(new Option("x", "producers",     true, "producer count"));
        options.addOption(new Option("y", "consumers",     true, "consumer count"));
        options.addOption(new Option("m", "ptxsize",       true, "producer tx size"));
        options.addOption(new Option("n", "ctxsize",       true, "consumer tx size"));
        options.addOption(new Option("c", "confirm",       true, "max unconfirmed publishes"));
        options.addOption(new Option("a", "autoack",       false,"auto ack"));
        options.addOption(new Option("A", "multiAckEvery", true, "multi ack every"));
        options.addOption(new Option("q", "qos",           true, "qos prefetch count"));
        options.addOption(new Option("s", "size",          true, "message size"));
        options.addOption(new Option("z", "time",          true, "time limit"));
        Option flag =     new Option("f", "flag",          true, "message flag");
        flag.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(flag);
        options.addOption(new Option("M", "framemax",      true, "frame max"));
        options.addOption(new Option("b", "heartbeat",     true, "heartbeat interval"));
        return options;
    }

    private static String strArg(CommandLine cmd, char opt, String def) {
        return cmd.getOptionValue(opt, def);
    }

    private static int intArg(CommandLine cmd, char opt, int def) {
        return Integer.parseInt(cmd.getOptionValue(opt, Integer.toString(def)));
    }

    private static List<?> lstArg(CommandLine cmd, char opt) {
        String[] vals = cmd.getOptionValues('f');
        if (vals == null) {
            vals = new String[] {};
        }
        return Arrays.asList(vals);
    }

    private static String formatRate(double rate) {
        if (rate == 0.0)    return String.format("%d", (long)rate);
        else if (rate < 1)  return String.format("%1.2f", rate);
        else if (rate < 10) return String.format("%1.1f", rate);
        else                return String.format("%d", (long)rate);
    }

    public static class Producer implements Runnable
    {
        private AMQSession channel;
        private AMQDestination destination;
        private String  id;
        private boolean mandatory;
        private boolean immediate;
        private boolean persistent;
        private int     txSize;
        private int     rateLimit;
        private long    timeLimit;

        private Stats   stats;

        private byte[]  message;

        private long    startTime;
        private long    lastStatsTime;
        private int     msgCount;
        private BasicMessageProducer publisher;

        public Producer(AMQSession channel, AMQDestination destination, String id,
                        List<?> flags, int txSize,
                        int rateLimit, int minMsgSize, int timeLimit,
                        long confirm, Stats stats)
                throws IOException {

            this.channel      = channel;
            this.destination = destination;
            this.id           = id;
            this.mandatory    = flags.contains("mandatory");
            this.immediate    = flags.contains("immediate");
            this.persistent   = flags.contains("persistent");
            this.txSize       = txSize;
            this.rateLimit    = rateLimit;
            this.timeLimit    = 1000L * timeLimit;
            this.message      = new byte[minMsgSize];
            this.stats        = stats;
            try {
                this.publisher = channel.createProducer(destination, this.mandatory, this.immediate);
            } catch (JMSException e) {
                throw new IOException(e);
            }
        }

        public void run() {

            long now = startTime = lastStatsTime = System.currentTimeMillis();
            msgCount = 0;
            int totalMsgCount = 0;

            try {

                while (timeLimit == 0 || now < startTime + timeLimit) {
                    delay(now);
                    publish(createMessage(totalMsgCount));
                    totalMsgCount++;
                    msgCount++;

                    if (txSize != 0 && totalMsgCount % txSize == 0) {
                        try {
                            channel.commit();
                        } catch (JMSException e) {
                            throw new IOException(e);
                        }
                    }
                    now = System.currentTimeMillis();
                    stats.handleSend();
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException (e);
            }

            System.out.println("sending rate avg: " +
                    formatRate(totalMsgCount * 1000.0 / (now - startTime)) +
                    " msg/s");

        }

        private void publish(byte[] msg)
                throws IOException {
            BytesMessage m;
            try {
                m = channel.createBytesMessage();
            } catch (JMSException e) {
                throw new IOException(e);
            }
            try {
                m.writeBytes(msg);
                publisher.send(m, persistent ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT);
            } catch (JMSException e) {
                throw new IOException(e);
            }
        }

        private void delay(long now)
                throws InterruptedException {

            long elapsed = now - lastStatsTime;
            //example: rateLimit is 5000 msg/s,
            //10 ms have elapsed, we have sent 200 messages
            //the 200 msgs we have actually sent should have taken us
            //200 * 1000 / 5000 = 40 ms. So we pause for 40ms - 10ms
            long pause = rateLimit == 0 ?
                    0 : (msgCount * 1000L / rateLimit - elapsed);
            if (pause > 0) {
                Thread.sleep(pause);
            }
        }

        private byte[] createMessage(int sequenceNumber)
                throws IOException {

            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(acc);
            long nano = System.nanoTime();
            d.writeInt(sequenceNumber);
            d.writeLong(nano);
            d.flush();
            acc.flush();
            byte[] m = acc.toByteArray();
            if (m.length <= message.length) {
                System.arraycopy(m, 0, message, 0, m.length);
                return message;
            } else {
                return m;
            }
        }

    }

    public static class Consumer implements Runnable {

        private AMQSession          channel;
        private String           id;
        private AMQDestination destination;
        private int              txSize;
        private boolean          autoAck;
        private int              multiAckEvery;
        private Stats            stats;
        private long             timeLimit;
        private QueueReceiver receiver;

        public Consumer(AMQSession channel, String id,
                        AMQDestination destination, int txSize, boolean autoAck,
                        int multiAckEvery, Stats stats, int timeLimit) {

            this.channel       = channel;
            this.id            = id;
            this.destination = destination;
            this.txSize        = txSize;
            this.autoAck       = autoAck;
            this.multiAckEvery = multiAckEvery;
            this.stats         = stats;
            this.timeLimit     = 1000L * timeLimit;
            try {
                this.receiver = channel.createQueueReceiver(destination);
            } catch (JMSException e) {
                throw new RuntimeException(e);
            }
        }

        public void run() {

            long now;
            long startTime;
            startTime = now = System.currentTimeMillis();
            int totalMsgCount = 0;

            try {
                while (timeLimit == 0 || now < startTime + timeLimit) {
                    JMSBytesMessage m;
                    if (timeLimit == 0) {
                        m = (JMSBytesMessage) receiver.receive();
                    } else {
                        m = (JMSBytesMessage) receiver.receive(startTime + timeLimit - now);
                        if (m == null) break;
                    }
                    totalMsgCount++;

                    int bodyLen = (int) m.getBodyLength();
                    byte[] body = new byte[bodyLen];
                    m.readBytes(body);
                    DataInputStream d = new DataInputStream(new ByteArrayInputStream(body));
                    d.readInt();
                    long msgNano = d.readLong();
                    long nano = System.nanoTime();

                    if (!autoAck) {
                        if (multiAckEvery == 0) {
                            channel.acknowledgeMessage(m.getDeliveryTag(), false);
                        } else if (totalMsgCount % multiAckEvery == 0) {
                            channel.acknowledgeMessage(m.getDeliveryTag(), true);
                        }
                    }

                    if (txSize != 0 && totalMsgCount % txSize == 0) {
                        channel.commit();
                    }

                    now = System.currentTimeMillis();

                    stats.handleRecv(id.equals(((AMQDestination) m.getJMSDestination()).getRoutingKey().asString()) ? (nano - msgNano) : 0L);
                }

            } catch (JMSException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            long elapsed = now - startTime;
            if (elapsed > 0) {
                System.out.println("recving rate avg: " +
                        formatRate(totalMsgCount * 1000.0 / elapsed) +
                        " msg/s");
            }
        }
    }

    public static class Stats {

        private long    interval;
        private boolean sendStatsEnabled;
        private boolean recvStatsEnabled;
        private boolean returnStatsEnabled;
        private boolean confirmStatsEnabled;

        private long    startTime;
        private long    lastStatsTime;

        private int     sendCount;
        private int     returnCount;
        private int     confirmCount;
        private int     nackCount;
        private int     recvCount;

        private int     latencyCount;
        private long    minLatency;
        private long    maxLatency;
        private long    cumulativeLatency;

        public Stats(long interval,
                     boolean sendStatsEnabled, boolean recvStatsEnabled,
                     boolean returnStatsEnabled, boolean confirmStatsEnabled) {
            this.interval            = interval;
            this.sendStatsEnabled    = sendStatsEnabled;
            this.recvStatsEnabled    = recvStatsEnabled;
            this.returnStatsEnabled  = returnStatsEnabled;
            this.confirmStatsEnabled = confirmStatsEnabled;
            startTime = System.currentTimeMillis();
            reset(startTime);
        }

        private void reset(long t) {
            lastStatsTime     = t;

            sendCount         = 0;
            returnCount       = 0;
            confirmCount      = 0;
            nackCount         = 0;
            recvCount         = 0;

            latencyCount      = 0;
            minLatency        = Long.MAX_VALUE;
            maxLatency        = Long.MIN_VALUE;
            cumulativeLatency = 0L;
        }

        private void showRate(String descr, long count, boolean display,
                              long elapsed) {
            if (display) {
                System.out.print(", " + descr + ": " + formatRate(1000.0 * count / elapsed) + " msg/s");
            }
        }

        private void report() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastStatsTime;

            if (elapsed >= interval) {
                System.out.print("time: " + String.format("%.3f", (now - startTime)/1000.0) + "s");

                showRate("sent",      sendCount,    sendStatsEnabled,                        elapsed);
                showRate("returned",  returnCount,  sendStatsEnabled && returnStatsEnabled,  elapsed);
                showRate("confirmed", confirmCount, sendStatsEnabled && confirmStatsEnabled, elapsed);
                showRate("nacked",    nackCount,    sendStatsEnabled && confirmStatsEnabled, elapsed);
                showRate("received",  recvCount,    recvStatsEnabled,                        elapsed);

                System.out.print((latencyCount > 0 ?
                        ", min/avg/max latency: " +
                                minLatency/1000L + "/" +
                                cumulativeLatency / (1000L * latencyCount) + "/" +
                                maxLatency/1000L + " microseconds" :
                        ""));

                System.out.println();
                reset(now);
            }
        }


        public synchronized void handleSend() {
            sendCount++;
            report();
        }

        public synchronized void handleReturn() {
            returnCount++;
            report();
        }

        public synchronized void handleConfirm(int numConfirms) {
            confirmCount+=numConfirms;
            report();
        }

        public synchronized void handleNack(int numAcks) {
            nackCount+=numAcks;
            report();
        }

        public synchronized void handleRecv(long latency) {
            recvCount++;
            if (latency > 0) {
                minLatency = Math.min(minLatency, latency);
                maxLatency = Math.max(maxLatency, latency);
                cumulativeLatency += latency;
                latencyCount++;
            }
            report();
        }

    }

}
