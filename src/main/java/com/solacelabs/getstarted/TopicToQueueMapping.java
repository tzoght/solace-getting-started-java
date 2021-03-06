/**
 *  Copyright 2015-2016 Solace Systems, Inc. All rights reserved.
 * 
 *  http://www.solacesystems.com
 * 
 *  This source is distributed under the terms and conditions of
 *  any contract or license agreement between Solace Systems, Inc.
 *  ("Solace") and you or your company. If there are no licenses or
 *  contracts in place use of this source is not authorized. This 
 *  source is provided as is and is not supported by Solace unless
 *  such support is provided for under an agreement signed between 
 *  you and Solace.
 */

package com.solacelabs.getstarted;

import java.util.concurrent.CountDownLatch;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.CapabilityType;
import com.solacesystems.jcsmp.Consumer;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.EndpointProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishEventHandler;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;

public class TopicToQueueMapping  {
    
    final int count = 5;
    final CountDownLatch latch = new CountDownLatch(count); // used for
    
    class SimplePrintingMessageListener implements XMLMessageListener {
        public void onReceive(BytesXMLMessage msg) {
            if (msg instanceof TextMessage) {
                System.out.printf("TextMessage received: '%s'%n", ((TextMessage) msg).getText());
            } else {
                System.out.println("Message received.");
            }
            System.out.printf("Message Dump:%n%s%n", msg.dump());
            
            msg.ackMessage();
            latch.countDown(); // unblock main thread
        }

        public void onException(JCSMPException e) {
            System.out.printf("Consumer received exception: %s%n", e);
            latch.countDown(); // unblock main thread
        }
    }

    void run(String[] args) throws JCSMPException {

        System.out.println("TopicToQueueMapping initializing...");
        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, args[0]); // msg-backbone ip:port
        properties.setProperty(JCSMPProperties.VPN_NAME, "default"); // message-vpn
        properties.setProperty(JCSMPProperties.USERNAME, "clientUsername"); // client-username (assumes no password)
        
        // Make sure that the session is tolerant of the subscription already existing on the queue.
        properties.setProperty(JCSMPProperties.IGNORE_DUPLICATE_SUBSCRIPTION_ERROR, true);
        
        final JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();
        
        // Confirm the current session supports the capabilities required.
        if (session.isCapable(CapabilityType.PUB_GUARANTEED) && 
            session.isCapable(CapabilityType.SUB_FLOW_GUARANTEED) && 
            session.isCapable(CapabilityType.ENDPOINT_MANAGEMENT) && 
            session.isCapable(CapabilityType.QUEUE_SUBSCRIPTIONS)) {
            System.out.println("All required capabilities supported!");
        } else {
            System.out.println("Missing required capability.");
            System.out.println("Capability - PUB_GUARANTEED: " + session.isCapable(CapabilityType.PUB_GUARANTEED));
            System.out.println("Capability - SUB_FLOW_GUARANTEED: " + session.isCapable(CapabilityType.SUB_FLOW_GUARANTEED));
            System.out.println("Capability - ENDPOINT_MANAGEMENT: " + session.isCapable(CapabilityType.ENDPOINT_MANAGEMENT));
            System.out.println("Capability - QUEUE_SUBSCRIPTIONS: " + session.isCapable(CapabilityType.QUEUE_SUBSCRIPTIONS));
            System.exit(1);
        }
        
        Queue queue = JCSMPFactory.onlyInstance().createQueue("Q/tutorial/topicToQueueMapping");
        
        /*
         * Provision a new queue on the appliance, ignoring if it already
         * exists. Set permissions, access type, quota (100MB), and provisioning flags.
         */
        System.out.printf("Provision queue '%s' on the appliance...", queue);
        EndpointProperties endpointProvisionProperties = new EndpointProperties();
        endpointProvisionProperties.setPermission(EndpointProperties.PERMISSION_DELETE);
        endpointProvisionProperties.setAccessType(EndpointProperties.ACCESSTYPE_EXCLUSIVE);
        endpointProvisionProperties.setQuota(100);
        session.provision(queue, endpointProvisionProperties, JCSMPSession.FLAG_IGNORE_ALREADY_EXISTS);
        
        // Add the Topic Subscription to the Queue.
        Topic tutorialTopic = JCSMPFactory.onlyInstance().createTopic("T/mapped/topic/sample");
        session.addSubscription(queue, tutorialTopic, JCSMPSession.WAIT_FOR_CONFIRM);
        
        /** Anonymous inner-class for handling publishing events */
        final XMLMessageProducer prod = session.getMessageProducer(
                new JCSMPStreamingPublishEventHandler() {
                    public void responseReceived(String messageID) {
                        System.out.printf("Producer received response for msg ID #%s%n",messageID);
                    }
                    public void handleError(String messageID, JCSMPException e, long timestamp) {
                        System.out.printf("Producer received error for msg ID %s @ %s - %s%n",
                                messageID,timestamp,e);
                    }
                });
        
        TextMessage msg =  JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        msg.setDeliveryMode(DeliveryMode.PERSISTENT);
        for (int i = 1; i <= count; i++) {
            msg.setText("Message number " + i);
            prod.send(msg, tutorialTopic);
        }
        System.out.println("Sent messages.");

        /*
         * Create a Flow to consume messages on the Queue. There should be 
         * five messages on the Queue.
         */
        
        ConsumerFlowProperties flow_prop = new ConsumerFlowProperties();
        flow_prop.setEndpoint(queue);
        Consumer cons = session.createFlow(new SimplePrintingMessageListener(), flow_prop);
        cons.start();

        try {
            latch.await(); // block here until message received, and latch will flip
        } catch (InterruptedException e) {
            System.out.println("I was awoken while waiting");
        }
        System.out.println("Finished consuming expected messages.");
          

        // Close consumer
        cons.close();
        System.out.println("Exiting.");
        session.closeSession();
    }
    

    
    


    public static void main(String... args) throws JCSMPException {

        // Check command line arguments
        if (args.length < 1) {
            System.out.println("Usage: TopicToQueueMapping <msg_backbone_ip:port>");
            System.exit(-1);
        }

        TopicToQueueMapping app = new TopicToQueueMapping();
        app.run(args);
    }
}
