/*
   Copyright 2022 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.management.bus.endpoints;

import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.security.RolesAllowed;

import org.apache.commons.lang3.StringUtils;
import org.springframework.messaging.Message;

import nl.nn.adapterframework.configuration.Configuration;
import nl.nn.adapterframework.core.Adapter;
import nl.nn.adapterframework.core.HasPhysicalDestination;
import nl.nn.adapterframework.core.HasSender;
import nl.nn.adapterframework.core.IListener;
import nl.nn.adapterframework.core.IMessageBrowser;
import nl.nn.adapterframework.core.IPipe;
import nl.nn.adapterframework.core.ISender;
import nl.nn.adapterframework.core.ITransactionalStorage;
import nl.nn.adapterframework.core.ListenerException;
import nl.nn.adapterframework.core.PipeForward;
import nl.nn.adapterframework.core.PipeLine;
import nl.nn.adapterframework.core.ProcessState;
import nl.nn.adapterframework.encryption.HasKeystore;
import nl.nn.adapterframework.encryption.KeystoreType;
import nl.nn.adapterframework.extensions.esb.EsbJmsListener;
import nl.nn.adapterframework.extensions.esb.EsbUtils;
import nl.nn.adapterframework.http.RestListener;
import nl.nn.adapterframework.jdbc.JdbcSenderBase;
import nl.nn.adapterframework.jms.JmsBrowser;
import nl.nn.adapterframework.jms.JmsListenerBase;
import nl.nn.adapterframework.management.bus.ActionSelector;
import nl.nn.adapterframework.management.bus.BusAction;
import nl.nn.adapterframework.management.bus.BusAware;
import nl.nn.adapterframework.management.bus.BusException;
import nl.nn.adapterframework.management.bus.BusMessageUtils;
import nl.nn.adapterframework.management.bus.BusTopic;
import nl.nn.adapterframework.management.bus.ResponseMessage;
import nl.nn.adapterframework.management.bus.TopicSelector;
import nl.nn.adapterframework.pipes.MessageSendingPipe;
import nl.nn.adapterframework.receivers.Receiver;
import nl.nn.adapterframework.util.AppConstants;
import nl.nn.adapterframework.util.ClassUtils;
import nl.nn.adapterframework.util.CredentialFactory;
import nl.nn.adapterframework.util.MessageKeeperMessage;
import nl.nn.adapterframework.util.RunState;
import nl.nn.adapterframework.webcontrol.api.FrankApiBase;

@BusAware("frank-management-bus")
@TopicSelector(BusTopic.ADAPTER)
public class AdapterStatus extends BusEndpointBase {
	private boolean showCountMessageLog = AppConstants.getInstance().getBoolean("messageLog.count.show", true);

	private static final String RECEIVERS="receivers";
	private static final String PIPES="pipes";
	private static final String MESSAGES="messages";

	public enum Expanded {
		NONE, ALL, RECEIVERS, MESSAGES, PIPES
	}

	@ActionSelector(BusAction.GET)
	@RolesAllowed({"IbisObserver", "IbisDataAdmin", "IbisAdmin", "IbisTester"})
	public Message<String> getAdapters(Message<?> message) {
		Expanded expanded = BusMessageUtils.getEnumHeader(message, "expanded", Expanded.class, Expanded.NONE);
		boolean showPendingMsgCount = BusMessageUtils.getBooleanHeader(message, "showPendingMsgCount", false);

		TreeMap<String, Object> adapterList = new TreeMap<>();
		for(Configuration config : getIbisManager().getConfigurations()) {
			for(Adapter adapter: config.getRegisteredAdapters()) {
				Map<String, Object> adapterInfo = getAdapterInformation(adapter, expanded, showPendingMsgCount);
				adapterList.put((String) adapterInfo.get("name"), adapterInfo);
			}
		}

		return ResponseMessage.ok(adapterList);
	}

	@ActionSelector(BusAction.FIND)
	@RolesAllowed({"IbisObserver", "IbisDataAdmin", "IbisAdmin", "IbisTester"})
	public Message<String> getAdapter(Message<?> message) {
		Expanded expanded = BusMessageUtils.getEnumHeader(message, "expanded", Expanded.class, Expanded.NONE);
		boolean showPendingMsgCount = BusMessageUtils.getBooleanHeader(message, "showPendingMsgCount", false);
		String configurationName = BusMessageUtils.getHeader(message, FrankApiBase.HEADER_CONFIGURATION_NAME_KEY);
		String adapterName = BusMessageUtils.getHeader(message, FrankApiBase.HEADER_ADAPTER_NAME_KEY);

		Adapter adapter = getAdapterByName(configurationName, adapterName);
		Map<String, Object> adapterInfo = getAdapterInformation(adapter, expanded, showPendingMsgCount);
		return ResponseMessage.ok(adapterInfo);
	}

	private Adapter getAdapterByName(String configurationName, String adapterName) {
		Configuration config = getConfigurationByName(configurationName);
		Adapter adapter = config.getRegisteredAdapter(adapterName);

		if(adapter == null){
			throw new BusException("adapter ["+adapterName+"] does not exist");
		}

		return adapter;
	}

	private Configuration getConfigurationByName(String configurationName) {
		if(StringUtils.isEmpty(configurationName)) {
			throw new BusException("no configuration name specified");
		}
		Configuration configuration = getIbisManager().getConfiguration(configurationName);
		if(configuration == null) {
			throw new BusException("configuration ["+configurationName+"] does not exists");
		}
		return configuration;
	}

	private Map<String, Object> getAdapterInformation(Adapter adapter, Expanded expandedFilter, boolean showPendingMsgCount) {
		Map<String, Object> adapterInfo = mapAdapter(adapter);
		switch (expandedFilter) {
		case ALL:
			adapterInfo.put(RECEIVERS, mapAdapterReceivers(adapter, showPendingMsgCount));
			adapterInfo.put(PIPES, mapAdapterPipes(adapter));
			adapterInfo.put(MESSAGES, mapAdapterMessages(adapter));
			break;
		case RECEIVERS:
			adapterInfo.put(RECEIVERS, mapAdapterReceivers(adapter, showPendingMsgCount));
			break;
		case PIPES:
			adapterInfo.put(PIPES, mapAdapterPipes(adapter));
			break;
		case MESSAGES:
			adapterInfo.put(MESSAGES, mapAdapterMessages(adapter));
		break;

		case NONE:
		default:
			//Don't add additional info
		}
		return adapterInfo;
	}


	private Map<String, Object> addCertificateInfo(HasKeystore s) {
		String certificate = s.getKeystore();
		if (certificate == null || StringUtils.isEmpty(certificate))
			return null;

		Map<String, Object> certElem = new HashMap<>(4);
		certElem.put("name", certificate);
		String certificateAuthAlias = s.getKeystoreAuthAlias();
		certElem.put("authAlias", certificateAuthAlias);
		URL certificateUrl = ClassUtils.getResourceURL(s, s.getKeystore());
		if (certificateUrl == null) {
			certElem.put("url", "");
			certElem.put("info", "*** ERROR ***");
		} else {
			certElem.put("url", certificateUrl.toString());
			String certificatePassword = s.getKeystorePassword();
			CredentialFactory certificateCf = new CredentialFactory(certificateAuthAlias, null, certificatePassword);
			KeystoreType keystoreType = s.getKeystoreType();
			certElem.put("info", getCertificateInfo(certificateUrl, certificateCf.getPassword(), keystoreType, "Certificate chain"));
		}
		return certElem;
	}

	private ArrayList<Object> getCertificateInfo(final URL url, final String password, KeystoreType keystoreType, String prefix) {
		ArrayList<Object> certificateList = new ArrayList<>();
		try (InputStream stream = url.openStream()) {
			KeyStore keystore = KeyStore.getInstance(keystoreType.name());
			keystore.load(stream, password != null ? password.toCharArray() : null);
			if (log.isInfoEnabled()) {
				Enumeration<String> aliases = keystore.aliases();
				while (aliases.hasMoreElements()) {
					String alias =  aliases.nextElement();
					ArrayList<Object> infoElem = new ArrayList<>();
					infoElem.add(prefix + " '" + alias + "':");
					Certificate trustedcert = keystore.getCertificate(alias);
					if (trustedcert instanceof X509Certificate) {
						X509Certificate cert = (X509Certificate) trustedcert;
						infoElem.add("Subject DN: " + cert.getSubjectDN());
						infoElem.add("Signature Algorithm: " + cert.getSigAlgName());
						infoElem.add("Valid from: " + cert.getNotBefore());
						infoElem.add("Valid until: " + cert.getNotAfter());
						infoElem.add("Issuer: " + cert.getIssuerDN());
					}
					certificateList.add(infoElem);
				}
			}
		} catch (Exception e) {
			certificateList.add("*** ERROR ***");
		}
		return certificateList;
	}

	private ArrayList<Object> mapAdapterPipes(Adapter adapter) {
		if(!adapter.configurationSucceeded())
			return null;
		PipeLine pipeline = adapter.getPipeLine();
		int totalPipes = pipeline.getPipes().size();
		ArrayList<Object> pipes = new ArrayList<>(totalPipes);

		for (int i=0; i<totalPipes; i++) {
			Map<String, Object> pipesInfo = new HashMap<>();
			IPipe pipe = pipeline.getPipe(i);
			Map<String, PipeForward> pipeForwards = pipe.getForwards();

			String pipename = pipe.getName();

			Map<String, String> forwards = new HashMap<>();
			for (PipeForward fwrd : pipeForwards.values()) {
				forwards.put(fwrd.getName(), fwrd.getPath());
			}

			pipesInfo.put("name", pipename);
			pipesInfo.put("forwards", forwards);
			if (pipe instanceof HasKeystore) {
				HasKeystore s = (HasKeystore) pipe;
				Map<String, Object> certInfo = addCertificateInfo(s);
				if(certInfo != null)
					pipesInfo.put("certificate", certInfo);
			}
			if (pipe instanceof MessageSendingPipe) {
				MessageSendingPipe msp=(MessageSendingPipe)pipe;
				ISender sender = msp.getSender();
				pipesInfo.put("sender", ClassUtils.nameOf(sender));
				if (sender instanceof HasKeystore) {
					HasKeystore s = (HasKeystore) sender;
					Map<String, Object> certInfo = addCertificateInfo(s);
					if(certInfo != null)
						pipesInfo.put("certificate", certInfo);
				}
				if (sender instanceof HasPhysicalDestination) {
					pipesInfo.put("destination",((HasPhysicalDestination)sender).getPhysicalDestinationName());
				}
				if (sender instanceof JdbcSenderBase) {
					pipesInfo.put("isJdbcSender", true);
				}
				IListener<?> listener = msp.getListener();
				if (listener!=null) {
					pipesInfo.put("listenerName", listener.getName());
					pipesInfo.put("listenerClass", ClassUtils.nameOf(listener));
					if (listener instanceof HasPhysicalDestination) {
						String pd = ((HasPhysicalDestination)listener).getPhysicalDestinationName();
						pipesInfo.put("listenerDestination", pd);
					}
				}
				ITransactionalStorage<?> messageLog = msp.getMessageLog();
				if (messageLog!=null) {
					mapPipeMessageLog(messageLog, pipesInfo, adapter.getRunState() == RunState.STARTED);
				} else if(sender instanceof ITransactionalStorage) { // in case no message log specified
					ITransactionalStorage<?> store = (ITransactionalStorage<?>) sender;
					mapPipeMessageLog(store, pipesInfo, adapter.getRunState() == RunState.STARTED);
					pipesInfo.put("isSenderTransactionalStorage", true);
				}
			}
			pipes.add(pipesInfo);
		}
		return pipes;
	}

	private void mapPipeMessageLog(ITransactionalStorage<?> store, Map<String, Object> data, boolean isStarted) {
		data.put("hasMessageLog", true);
		String messageLogCount;
		try {
			if (showCountMessageLog && isStarted) {
				messageLogCount=""+store.getMessageCount();
			} else {
				messageLogCount="?";
			}
		} catch (Exception e) {
			log.warn("Cannot determine number of messages in messageLog ["+store.getName()+"]", e);
			messageLogCount="error";
		}
		data.put("messageLogCount", messageLogCount);

		Map<String, Object> message = new HashMap<>();
		message.put("name", store.getName());
		message.put("type", "log");
		message.put("slotId", store.getSlotId());
		message.put("count", messageLogCount);
		data.put("message", message);
	}

	private Object getMessageCount(RunState runState, IMessageBrowser<?> ts) {
		if(runState == RunState.STARTED) {
			try {
				return ts.getMessageCount();
			} catch (Exception e) {
				log.warn("Cannot determine number of messages in MessageBrowser ["+ClassUtils.nameOf(ts)+"]", e);
				return "error";
			}
		} else {
			return "?";
		}
	}

	private ArrayList<Object> mapAdapterReceivers(Adapter adapter, boolean showPendingMsgCount) {
		ArrayList<Object> receivers = new ArrayList<>();

		for (Receiver<?> receiver: adapter.getReceivers()) {
			Map<String, Object> receiverInfo = new HashMap<>();

			RunState receiverRunState = receiver.getRunState();

			receiverInfo.put("name", receiver.getName());
			receiverInfo.put("state", receiverRunState.name().toLowerCase());

			Map<String, Object> messages = new HashMap<>(3);
			messages.put("received", receiver.getMessagesReceived());
			messages.put("retried", receiver.getMessagesRetried());
			messages.put("rejected", receiver.getMessagesRejected());
			receiverInfo.put(MESSAGES, messages);

			Set<ProcessState> knownStates = receiver.knownProcessStates();
			Map<ProcessState, Object> tsInfo = new LinkedHashMap<>();
			for (ProcessState state : knownStates) {
				IMessageBrowser<?> ts = receiver.getMessageBrowser(state);
				if(ts != null) {
					Map<String, Object> info = new HashMap<>();
					info.put("numberOfMessages", getMessageCount(receiverRunState, ts));
					info.put("name", state.getName());
					tsInfo.put(state, info);
				}
			}
			receiverInfo.put("transactionalStores", tsInfo);

			ISender sender=null;
			IListener<?> listener=receiver.getListener();
			if(listener != null) {
				Map<String, Object> listenerInfo = new HashMap<>();
				listenerInfo.put("name", listener.getName());
				listenerInfo.put("class", ClassUtils.nameOf(listener));
				if (listener instanceof HasPhysicalDestination) {
					String pd = ((HasPhysicalDestination)receiver.getListener()).getPhysicalDestinationName();
					listenerInfo.put("destination", pd);
				}
				if (listener instanceof HasSender) {
					sender = ((HasSender)listener).getSender();
				}

				boolean isRestListener = (listener instanceof RestListener);
				listenerInfo.put("isRestListener", isRestListener);
				if (isRestListener) {
					RestListener rl = (RestListener) listener;
					listenerInfo.put("restUriPattern", rl.getRestUriPattern());
					listenerInfo.put("isView", rl.isView());
				}

				receiverInfo.put("listener", listenerInfo);
			}

			if ((listener instanceof JmsListenerBase) && showPendingMsgCount) {
				JmsListenerBase jlb = (JmsListenerBase) listener;
				JmsBrowser<javax.jms.Message> jmsBrowser;
				if (StringUtils.isEmpty(jlb.getMessageSelector())) {
					jmsBrowser = new JmsBrowser<>();
				} else {
					jmsBrowser = new JmsBrowser<>(jlb.getMessageSelector());
				}
				jmsBrowser.setName("MessageBrowser_" + jlb.getName());
				jmsBrowser.setJmsRealm(jlb.getJmsRealmName());
				jmsBrowser.setDestinationName(jlb.getDestinationName());
				jmsBrowser.setDestinationType(jlb.getDestinationType());
				receiverInfo.put("pendingMessagesCount", getMessageCount(receiverRunState, jmsBrowser));
			}
			boolean isEsbJmsFFListener = false;
			if (listener instanceof EsbJmsListener) {
				EsbJmsListener ejl = (EsbJmsListener) listener;
				if(ejl.getMessageProtocol() != null) {
					if (ejl.getMessageProtocol().equalsIgnoreCase("FF")) {
						isEsbJmsFFListener = true;
					}
					if(showPendingMsgCount) {
						String esbNumMsgs = EsbUtils.getQueueMessageCount(ejl);
						if (esbNumMsgs == null) {
							esbNumMsgs = "?";
						}
						receiverInfo.put("esbPendingMessagesCount", esbNumMsgs);
					}
				}
			}
			receiverInfo.put("isEsbJmsFFListener", isEsbJmsFFListener);

			ISender rsender = receiver.getSender();
			if (rsender!=null) { // this sender has preference, but avoid overwriting listeners sender with null
				sender=rsender;
			}
			if (sender != null) {
				receiverInfo.put("senderName", sender.getName());
				receiverInfo.put("senderClass", ClassUtils.nameOf(sender));
				if (sender instanceof HasPhysicalDestination) {
					String pd = ((HasPhysicalDestination)sender).getPhysicalDestinationName();
					receiverInfo.put("senderDestination", pd);
				}
			}
			if (receiver.isThreadCountReadable()) {
				receiverInfo.put("threadCount", receiver.getCurrentThreadCount());
				receiverInfo.put("maxThreadCount", receiver.getMaxThreadCount());
			}
			if (receiver.isThreadCountControllable()) {
				receiverInfo.put("threadCountControllable", "true");
			}
			receivers.add(receiverInfo);
		}
		return receivers;
	}

	private ArrayList<Object> mapAdapterMessages(Adapter adapter) {
		int totalMessages = adapter.getMessageKeeper().size();
		ArrayList<Object> messages = new ArrayList<>(totalMessages);
		for (int t=0; t<totalMessages; t++) {
			Map<String, Object> message = new HashMap<>();
			MessageKeeperMessage msg = adapter.getMessageKeeper().getMessage(t);

			message.put("message", msg.getMessageText());
			message.put("date", msg.getMessageDate());
			message.put("level", msg.getMessageLevel());
			message.put("capacity", adapter.getMessageKeeper().capacity());

			messages.add(message);
		}
		return messages;
	}

	private Map<String, Object> mapAdapter(Adapter adapter) {
		Map<String, Object> adapterInfo = new HashMap<>();
		Configuration config = adapter.getConfiguration();

		String adapterName = adapter.getName();
		adapterInfo.put("name", adapterName);
		adapterInfo.put("description", adapter.getDescription());
		adapterInfo.put("configuration", config.getName() );
		RunState adapterRunState = adapter.getRunState();
		adapterInfo.put("started", adapterRunState==RunState.STARTED);
		String state = adapterRunState.toString().toLowerCase().replace("*", "");
		adapterInfo.put("state", state);

		adapterInfo.put("configured", adapter.configurationSucceeded());
		adapterInfo.put("upSince", adapter.getStatsUpSinceDate().getTime());
		Date lastMessage = adapter.getLastMessageDateDate();
		if(lastMessage != null) {
			adapterInfo.put("lastMessage", lastMessage.getTime());
			adapterInfo.put("messagesInProcess", adapter.getNumOfMessagesInProcess());
			adapterInfo.put("messagesProcessed", adapter.getNumOfMessagesProcessed());
			adapterInfo.put("messagesInError", adapter.getNumOfMessagesInError());
		}

		Iterator<Receiver<?>> it = adapter.getReceivers().iterator();
		int errorStoreMessageCount = 0;
		int messageLogMessageCount = 0;
		while(it.hasNext()) {
			Receiver<?> rcv = it.next();
			if(rcv.isNumberOfExceptionsCaughtWithoutMessageBeingReceivedThresholdReached()) {
				adapterInfo.put("receiverReachedMaxExceptions", "true");
			}

			if(rcv.getRunState() == RunState.STARTED) {
				IMessageBrowser<?> esmb = rcv.getMessageBrowser(ProcessState.ERROR);
				if(esmb != null) {
					try {
						errorStoreMessageCount += esmb.getMessageCount();
					} catch (ListenerException e) {
						log.warn("Cannot determine number of messages in errorstore of ["+rcv.getName()+"]", e);
					}
				}
				IMessageBrowser<?> mlmb = rcv.getMessageBrowser(ProcessState.DONE);
				if(mlmb != null) {
					try {
						messageLogMessageCount += mlmb.getMessageCount();
					} catch (ListenerException e) {
						log.warn("Cannot determine number of messages in messagelog of ["+rcv.getName()+"]", e);
					}
				}
			}
		}
		if(errorStoreMessageCount != 0) {
			adapterInfo.put("errorStoreMessageCount", errorStoreMessageCount);
		}
		if(messageLogMessageCount != 0) {
			adapterInfo.put("messageLogMessageCount", messageLogMessageCount);
		}

		return adapterInfo;
	}
}