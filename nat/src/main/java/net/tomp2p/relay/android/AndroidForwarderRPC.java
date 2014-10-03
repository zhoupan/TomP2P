package net.tomp2p.relay.android;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.tomp2p.connection.PeerConnection;
import net.tomp2p.connection.Responder;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.message.Buffer;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.relay.BaseRelayForwarderRPC;
import net.tomp2p.utils.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.android.gcm.server.Result;
import com.google.android.gcm.server.Sender;

/**
 * Manages the mapping between a peer address and the registration id. The registration id is sent by the
 * mobile device when the relay is set up.
 * 
 * @author Nico Rutishauser
 *
 */
public class AndroidForwarderRPC extends BaseRelayForwarderRPC implements MessageBufferListener {

	private static final Logger LOG = LoggerFactory.getLogger(AndroidForwarderRPC.class);

	private final AndroidRelayConfiguration config;
	private final Sender sender;
	private String registrationId;
	private final MessageBuffer buffer;
	private final List<Pair<Buffer, Buffer>> readyToSend;

	public AndroidForwarderRPC(Peer peer, PeerConnection peerConnection, AndroidRelayConfiguration config,
			String authenticationToken, String registrationId) {
		super(peer, peerConnection);
		this.config = config;
		this.registrationId = registrationId;
		this.sender = new Sender(authenticationToken);
		this.buffer = new MessageBuffer(config.bufferCountLimit(), config.bufferSizeLimit(), config.bufferAgeLimit(), this);
		this.readyToSend = Collections.synchronizedList(new ArrayList<Pair<Buffer, Buffer>>());
	}

	@Override
	public boolean peerFound(PeerAddress remotePeer, PeerAddress referrer, PeerConnection peerConnection) {
		// ignore it
		return false;
	}

	@Override
	public FutureDone<Message> forwardToUnreachable(Message message) {
		// create temporal OK message
		final FutureDone<Message> futureDone = new FutureDone<Message>();
		final Message response = createResponseMessage(message, Type.PARTIALLY_OK);
		response.recipient(message.sender());
		response.sender(unreachablePeerAddress());

		try {
			buffer.addMessage(message);
		} catch (Exception e) {
			LOG.error("Cannot encode the message", e);
			futureDone.done(createResponseMessage(message, Type.EXCEPTION));
		}

		return futureDone.done(response);
	}

	@Override
	protected void handlePing(Message message, final Responder responder, PeerAddress sender) {
		// Check if the mobile device is still alive by checking the elements in the queue. If the queue
		// is twice its intended size (or more), the device is probably offline
		if (readyToSend.size() < 2) {
			LOG.debug("Device {} seems to be alive", registrationId);
			// responder.response(createResponseMessage(message, Type.OK));
		} else {
			LOG.warn("Device {} did not request the queue for a long time", registrationId);
			// responder.response(createResponseMessage(message, Type.DENIED));
		}

		// TODO just for testing:
		final FutureDone<Message> futureDone = forwardToUnreachable(message);
		futureDone.addListener(new BaseFutureAdapter<FutureDone<Message>>() {
			@Override
			public void operationComplete(FutureDone<Message> future) throws Exception {
				responder.response(futureDone.object());
			}
		});
	}

	/**
	 * Tickle the device through Google Cloud Messaging
	 */
	private FutureDone<Void> sendTickleMessage() {
		// the collapse key is the relay's peerId
		final com.google.android.gcm.server.Message tickleMessage = new com.google.android.gcm.server.Message.Builder()
				.collapseKey(relayPeerId().toString()).build();
		final FutureDone<Void> future = new FutureDone<Void>();
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					LOG.debug("Send GCM message to the device {}", registrationId);
					Result result = sender.send(tickleMessage, registrationId, config.gcmSendRetries());
					if (result.getMessageId() == null) {
						LOG.error("Could not send the tickle messge. Reason: {}", result.getErrorCodeName());
						future.failed("Cannot send message over GCM. Reason: " + result.getErrorCodeName());
					} else if (result.getCanonicalRegistrationId() != null) {
						LOG.debug("Update the registration id {} to canonical name {}", registrationId,
								result.getCanonicalRegistrationId());
						registrationId = result.getCanonicalRegistrationId();
						future.done();
					} else {
						LOG.debug("Successfully sent the message over GCM");
						future.done();
					}
				} catch (IOException e) {
					LOG.error("Cannot send tickle message to device {}", registrationId, e);
					future.failed(e);
				}
			}
		}, "Send-GCM-Tickle-Message").start();

		return future;
	}

	@Override
	public void bufferFull(Buffer sizeBuffer, Buffer messageBuffer) {
		synchronized (readyToSend) {
			readyToSend.add(new Pair<Buffer, Buffer>(sizeBuffer, messageBuffer));
			sendTickleMessage();
		}
	}

	/**
	 * Retrieves the messages that are ready to send. Ready to send means that they have been buffered and the
	 * Android device has already been notified.
	 * 
	 * @return a pair of buffers. The first element contains the size of all messages, the second element the
	 *         data
	 */
	public Pair<Buffer, Buffer> getBufferedMessages() {
		ByteBuf sizeBuffer = Unpooled.buffer();
		ByteBuf dataBuffer = Unpooled.buffer();
		synchronized (readyToSend) {
			for (Pair<Buffer, Buffer> rts : readyToSend) {
				sizeBuffer.writeBytes(rts.element0().buffer());
				dataBuffer.writeBytes(rts.element1().buffer());
			}

			readyToSend.clear();
		}

		return new Pair<Buffer, Buffer>(new Buffer(sizeBuffer), new Buffer(dataBuffer));
	}
}
