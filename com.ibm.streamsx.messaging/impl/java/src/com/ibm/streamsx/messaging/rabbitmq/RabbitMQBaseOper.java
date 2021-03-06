/*******************************************************************************
 * Copyright (C) 2015, MOHAMED-ALI SAID and International Business Machines
 * All Rights Reserved
 *******************************************************************************/

package com.ibm.streamsx.messaging.rabbitmq;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.metrics.Metric;
import com.ibm.streams.operator.model.CustomMetric;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.Parameter;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Recoverable;

@Libraries({ "opt/downloaded/*"/*, "@RABBITMQ_HOME@" */})
public class RabbitMQBaseOper extends AbstractOperator {

	protected Channel channel;
	protected Connection connection;
	protected String username = "",
			password = "", exchangeName = "", exchangeType = "direct";
			
	protected List<String> hostAndPortList = new ArrayList<String>();
	protected Address[] addressArr; 
	private String vHost;
	private Boolean autoRecovery = true;

	protected AttributeHelper messageHeaderAH = new AttributeHelper("message_header"),
			routingKeyAH = new AttributeHelper("routing_key"),
			messageAH = new AttributeHelper("message");

	private final Logger trace = Logger.getLogger(this.getClass()
			.getCanonicalName());
	protected Boolean usingDefaultExchange = false;
	private String URI = "";
	private long networkRecoveryInterval = -1;
	
	private Metric isConnected;
	
	
	public synchronized void initialize(OperatorContext context)
			throws Exception {
		// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
	}

	public void initializeRabbitChannelAndConnection() throws MalformedURLException, URISyntaxException, NoSuchAlgorithmException,
			KeyManagementException, IOException, TimeoutException, InterruptedException, Exception {
		ConnectionFactory connectionFactory = setupConnectionFactory();
		
		// If we return from this without throwing an exception, then we 
		// have successfully connected
		connection = setupNewConnection(connectionFactory, URI, addressArr, isConnected);
		isConnected.setValue(1);
		
		channel = initializeExchange();
		
		trace.log(TraceLevel.INFO,
				"Initializing channel connection to exchange: " + exchangeName
						+ " of type: " + exchangeType + " as user: " + connectionFactory.getUsername());
		trace.log(TraceLevel.INFO,
				"Connection to host: " + connection.getAddress());
	}

	private ConnectionFactory setupConnectionFactory()
			throws MalformedURLException, URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
		ConnectionFactory connectionFactory = new ConnectionFactory();
		connectionFactory.setExceptionHandler(new RabbitMQConnectionExceptionHandler(isConnected));
		connectionFactory.setAutomaticRecoveryEnabled(autoRecovery);
		if (networkRecoveryInterval >= 0){
			connectionFactory.setNetworkRecoveryInterval(networkRecoveryInterval);
		}
		
		if (URI.isEmpty()){
			configureUsernameAndPassword(connectionFactory);
			if (vHost != null)
				connectionFactory.setVirtualHost(vHost);
			
			addressArr = buildAddressArray(hostAndPortList);
			
		} else{
			//use specified URI rather than username, password, vHost, hostname, etc
			if (!username.isEmpty() | !password.isEmpty() | vHost != null | !hostAndPortList.isEmpty()){
				trace.log(TraceLevel.WARNING, "You specified a URI, therefore username, password"
						+ ", vHost, and hostname parameters will be ignored.");
			}
			connectionFactory.setUri(URI);
		}
		return connectionFactory;
	}

	/*
	 * We will reconnect indefinitely following the same timeout that the connectionFactory uses
	 * for the automatic reconnection policy (if automatic recovery is enabled). 
	 */
	private Connection setupNewConnection(ConnectionFactory connectionFactory, String URI, Address[] addressArr, Metric isConnected) throws IOException, TimeoutException, InterruptedException {
		Connection connection = null;
		
		if (connectionFactory.isAutomaticRecoveryEnabled()){
			boolean connected = false;
			while (!connected){
				try{
					connection = getConnection(connectionFactory, URI, addressArr);
					connected = true;
				} catch (Exception e){
					e.printStackTrace();
					trace.log(TraceLevel.ERROR, "Failed to setup connection: " + e.getMessage());
					Thread.sleep(connectionFactory.getNetworkRecoveryInterval());
				}
			}
			((Recoverable) connection).addRecoveryListener(new AutoRecoveryListener(isConnected));
		} else {
			connection = getConnection(connectionFactory, URI, addressArr);
		}
		
		return connection;
	}

	private Connection getConnection(ConnectionFactory connectionFactory, String URI, Address[] addressArr)
			throws IOException, TimeoutException {
		Connection connection;
		if (URI.isEmpty()){
			connection = connectionFactory.newConnection(addressArr);
			trace.log(TraceLevel.INFO, "Creating a new connection based on an address list.");
		} else {
			connection = connectionFactory.newConnection();
			trace.log(TraceLevel.INFO, "Creating a new connection based on a provided URI.");
		}
		return connection;
	}

	private void configureUsernameAndPassword(
			ConnectionFactory connectionFactory) {
		if (username != ""){
			connectionFactory.setUsername(username);
			connectionFactory.setPassword(password);
			trace.log(TraceLevel.INFO, "Set username and password.");
		} else {
			trace.log(TraceLevel.INFO, "Defaults: " + connectionFactory.getUsername() + " " + connectionFactory.getPassword());				
		}
	}

	private Channel initializeExchange() throws IOException {
		Channel channel = connection.createChannel();
		try{
			//check to see if the exchange exists if not then it is the default exchange
			if ( !exchangeName.isEmpty()){
				channel.exchangeDeclarePassive(exchangeName);
				trace.log(TraceLevel.INFO, "Exchange was found, therefore no exchange will be declared.");
			} else {
				usingDefaultExchange = true;
				trace.log(TraceLevel.INFO, "Using the default exchange. Name \"\"");
			}
		} catch (IOException e){
			// if exchange doesn't exist, we will create it
			// we must also create a new channel since last one erred
			channel = connection.createChannel();
			// declare non-durable, auto-delete exchange
			channel.exchangeDeclare(exchangeName, exchangeType, false, true, null);
			trace.log(TraceLevel.INFO, "Exchange was not found, therefore non-durable exchange will be declared.");
		}
		return channel;
	}

	private Address[] buildAddressArray(List<String> hostsAndPorts) throws MalformedURLException {
		Address[] addrArr = new Address[hostsAndPorts.size()];
		int i = 0;
		for (String hostAndPort : hostsAndPorts){
			URL tmpURL = new URL("http://" + hostAndPort);
			addrArr[i++] = new Address(tmpURL.getHost(), tmpURL.getPort());
			trace.log(TraceLevel.INFO, "Adding: " + tmpURL.getHost() + ":"+ tmpURL.getPort());
		}
		trace.log(TraceLevel.INFO, "Built address array: \n" + addrArr.toString());
		
		return addrArr;
	}

	public void shutdown() throws IOException, TimeoutException {
		channel.close();
		try {
			connection.close();
		} catch (Exception e) {
			e.printStackTrace();
			trace.log(TraceLevel.ALL, "Exception at close: " + e.toString());
		}
	}

	public void initSchema(StreamSchema ss) throws Exception {
		Set<MetaType> supportedTypes = new HashSet<MetaType>();
		supportedTypes.add(MetaType.MAP);
		messageHeaderAH.initialize(ss, false, supportedTypes);
		supportedTypes.remove(MetaType.MAP);
		
		supportedTypes.add(MetaType.RSTRING);
		supportedTypes.add(MetaType.USTRING);
		
		routingKeyAH.initialize(ss, false, supportedTypes);
		
		supportedTypes.add(MetaType.BLOB);
		
		messageAH.initialize(ss, true, supportedTypes);

	}

	@Parameter(optional = true, description = "List of host and port in form: \\\"myhost1:3456\\\",\\\"myhost2:3456\\\".")
	public void setHostAndPort(List<String> value) {
		hostAndPortList.addAll(value);
	}

	@Parameter(optional = true, description = "Username for RabbitMQ authentication.")
	public void setUsername(String value) {
		username = value;
	}

	@Parameter(optional = true, description = "Password for RabbitMQ authentication.")
	public void setPassword(String value) {
		password = value;
	}
	
	@Parameter(optional = true, description = "Optional attribute. Name of the RabbitMQ exchange type. Default direct.")
	public void setExchangeType(String value) {
		exchangeType = value;
	}
	
	@Parameter(optional = true, description = "Convenience URI of form: amqp://userName:password@hostName:portNumber/virtualHost. If URI is specified, you cannot specify username, password, and host.")
	public void setURI(String value) {
		URI  = value;
	}

	@Parameter(optional = true, description = "Name of the attribute for the message. Default is \\\"message\\\".")
	public void setMessageAttribute(String value) {
		messageAH.setName(value);
	}

	@Parameter(optional = true, description = "Name of the attribute for the routing_key. Default is \\\"routing_key\\\".")
	public void setRoutingKeyAttribute(String value) {
		routingKeyAH.setName(value);
	}

	@Parameter(optional = true, description = "Name of the attribute for the message_header. Schema of type must be Map<ustring,ustring>. Default is \\\"message_header\\\".")
	public void setMsgHeaderAttribute(String value) {
		messageHeaderAH.setName(value);
	}
	
	@Parameter(optional = true, description = "Set Virtual Host. Default is null.")
	public void setVirtualHost(String value) {
		vHost = value; 
	}
	
	@Parameter(optional = true, description = "Have connections to RabbitMQ automatically recovered. Default is true.")
	public void setAutomaticRecovery(Boolean value) {
		autoRecovery = value; 
	}

	@Parameter(optional = true, description = "If automaticRecovery is set to true, this is the interval (in ms) that will be used between reconnection attempts. The default is 5000 ms.")
	public void setSetNetworkRecoveryInterval(long value) {
		networkRecoveryInterval  = value; 
	}
	
	@CustomMetric(name = "isConnected", kind = Metric.Kind.GAUGE,
		    description = "Describes whether we are currently connected to the RabbitMQ server.")
	public void setIsConnectedMetric(Metric isConnected) {
		this.isConnected = isConnected;
	}
}
