package ch.ethz.inf.vs.californium.network;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import ch.ethz.inf.vs.californium.CalifonriumLogger;
import ch.ethz.inf.vs.californium.coap.CoAP.Code;
import ch.ethz.inf.vs.californium.coap.CoAP.Type;
import ch.ethz.inf.vs.californium.coap.EmptyMessage;
import ch.ethz.inf.vs.californium.coap.Message;
import ch.ethz.inf.vs.californium.coap.Request;
import ch.ethz.inf.vs.californium.coap.Response;
import ch.ethz.inf.vs.californium.network.Exchange.KeyMID;
import ch.ethz.inf.vs.californium.network.Exchange.KeyToken;
import ch.ethz.inf.vs.californium.network.Exchange.Origin;
import ch.ethz.inf.vs.californium.network.dedupl.Deduplicator;
import ch.ethz.inf.vs.californium.network.dedupl.DeduplicatorFactory;
import ch.ethz.inf.vs.californium.network.layer.ExchangeForwarder;

public class Matcher {

	private final static Logger LOGGER = CalifonriumLogger.getLogger(Matcher.class);
	
	private boolean started;
	private ExchangeObserver exchangeObserver = new ExchangeObserverImpl();
	
	private ExchangeForwarder forwarder;
	
	/** The executor. */
	private ScheduledExecutorService executor;
	
	// TODO: Make per endpoint
	private AtomicInteger currendMID; 
	
	private ConcurrentHashMap<KeyMID, Exchange> exchangesByMID; // Outgoing
	private ConcurrentHashMap<KeyToken, Exchange> exchangesByToken;
	
	private ConcurrentHashMap<KeyToken, Exchange> ongoingExchanges; // for blockwise
	
	// TODO: Multicast Exchanges: should not be removed from deduplicator
	private Deduplicator deduplicator;
	// Idea: Only store acks/rsts and not the whole exchange. Responses should be sent CON.
	
	public Matcher(ExchangeForwarder forwarder, NetworkConfig config) {
		this.forwarder = forwarder;
		this.started = false;
		this.exchangesByMID = new ConcurrentHashMap<KeyMID, Exchange>();
		this.exchangesByToken = new ConcurrentHashMap<KeyToken, Exchange>();
		this.ongoingExchanges = new ConcurrentHashMap<KeyToken, Exchange>();

		DeduplicatorFactory factory = DeduplicatorFactory.getDeduplicatorFactory();
		this.deduplicator = factory.createDeduplicator(config);
		
		if (config.getBoolean(NetworkConfigDefaults.USE_RANDOM_MID_START))
			currendMID = new AtomicInteger(new Random().nextInt(1<<16));
		else currendMID = new AtomicInteger(0);
	}
	
	public synchronized void start() {
		if (started) return;
		else started = true;
		if (executor == null)
			throw new IllegalStateException("Matcher has no executor to schedule exchnage removal");
		deduplicator.start();
	}
	
	public synchronized void stop() {
		if (!started) return;
		else started = false;
		deduplicator.stop();
		clear();
	}
	
	public synchronized void setExecutor(ScheduledExecutorService executor) {
		deduplicator.setExecutor(executor);
		this.executor = executor;
	}
	
	public void sendRequest(Exchange exchange, Request request) {
		if (request.getMID() == Message.NONE)
			request.setMID(currendMID.getAndIncrement()%(1<<16));

		/*
		 * The request is a CON or NCON and must be prepared for these responses
		 * - CON  => ACK/RST/ACK+response/CON+response/NCON+response
		 * - NCON => RST/CON+response/NCON+response
		 * If this request goes lost, we do not get anything back.
		 */
		
		KeyMID idByMID = new KeyMID(request.getMID(), 
				request.getDestination().getAddress(), request.getDestinationPort());
		KeyToken idByTok = new KeyToken(request.getToken(),
				request.getDestination().getAddress(), request.getDestinationPort());
		
		exchange.setObserver(exchangeObserver);
		
		exchangesByMID.put(idByMID, exchange);
		exchangesByToken.put(idByTok, exchange);
	}

	public void sendResponse(Exchange exchange, Response response) {
		if (response.getMID() == Message.NONE)
			response.setMID(currendMID.getAndIncrement()%(1<<16));
		
		/*
		 * The response is a CON or NON or ACK and must be prepared for these
		 * - CON  => ACK/RST // we only care to stop retransmission
		 * - NCON => RST // we don't care
		 * - ACK  => nothing!
		 * If this response goes lost, we must be prepared to get the same 
		 * CON/NCON request with same MID again. We then find the corresponding
		 * exchange and the retransmissionlayer resends this response.
		 */
		
		// Insert CON and NON to match ACKs and RSTs to the exchange
		KeyMID idByMID = new KeyMID(response.getMID(), 
				response.getDestination().getAddress(), response.getDestinationPort());
		exchangesByMID.put(idByMID, exchange);
		
		if (exchange.getCurrentRequest().getCode() == Code.GET
				&& response.getOptions().hasBlock2()) {
			// Remember ongoing blockwise GET requests
			Request request = exchange.getRequest();
			KeyToken idByTok = new KeyToken(request.getToken(),
					request.getSource().getAddress(), request.getSourcePort());
			ongoingExchanges.put(idByTok, exchange);
		}
		
		if (response.getType() == Type.ACK || response.getType() == Type.NON) {
			// Since this is an ACK or NON, the exchange is over with sending this response.
			if (response.isLast()) {
				exchange.setComplete(true);
				exchangeObserver.completed(exchange);
			}
		} // else this is a CON and we need to wait for the ACK or RST
	}

	public void sendEmptyMessage(Exchange exchange, EmptyMessage message) {
		
		if (message.getType() == Type.RST && exchange != null) {
			// We have rejected the request or response
			exchange.setComplete(true);
			exchangeObserver.completed(exchange);
		}
		
		/*
		 * We do not expect any response for an empty message
		 */
		if (message.getMID() == Message.NONE)
			LOGGER.warning("Empy message "+ message+" has MID NONE // debugging");
	}

	public Exchange receiveRequest(Request request) {
		/*
		 * This request could be
		 *  - Complete origin request => deliver with new exchange
		 *  - One origin block        => deliver with ongoing exchange
		 *  - Complete duplicate request or one duplicate block (because client got no ACK) 
		 *      =>
		 * 		if ACK got lost => resend ACK
		 * 		if ACK+response got lost => resend ACK+response
		 * 		if nothing has been sent yet => do nothing
		 * (Retransmission is supposed to be done by the retransm. layer)
		 */
		
		KeyMID idByMID = new KeyMID(request.getMID(),
				request.getSource().getAddress(), request.getSourcePort());
		
		KeyToken idByTok = new KeyToken(request.getToken(),
				request.getSource().getAddress(), request.getSourcePort());
		
		if (!(
				(request.getOptions().hasBlock1() && request.getOptions().getBlock1().getNum()!=0)
				|| (request.getOptions().hasBlock2() && request.getOptions().getBlock2().getNum()!=0)
			) ) {
			LOGGER.fine("Create new exchange for remote request");
			// This request starts a new exchange
			Exchange exchange = new Exchange(request, Origin.REMOTE);
			
			Exchange previous = deduplicator.findPrevious(idByMID, exchange);
			if (previous == null) {
				if (request.getOptions().hasBlock1()) {
					ongoingExchanges.put(idByTok, exchange);
				}
				return exchange;
				
			} else {
				LOGGER.fine("Message is a duplicate, ignore: "+request);
				request.setDuplicate(true);
				return previous;
			}
			
		} else {
			// FIXME: When a Block2 is used to access only a certain block but
			// not the whole response, this also is a 'new Exchange'. At the
			// moment, a blockwise transfer must start with Block2(Num=0).
			
			LOGGER.fine("Lookup ongoing exchange");
			// This is a block of an ongoing request
			Exchange ongoing = ongoingExchanges.get(idByTok);
			if (ongoing != null) {
				
				Exchange prev = deduplicator.findPrevious(idByMID, ongoing);
				if (prev != null) {
					LOGGER.fine("Message is a duplicate, ignore: "+request);
					request.setDuplicate(true);
				}
				return ongoing;
		
			} else {
				// We have no ongoing exchange for that block. 
				// This might be a duplicate request of an already completed exchange
				Exchange prev = deduplicator.find(idByMID);
				if (prev != null) {
					LOGGER.fine("Message is a duplicate, ignore: "+request);
					request.setDuplicate(true);
					return prev;
				}
				
				// This request fits no exchange
				EmptyMessage rst = EmptyMessage.newACK(request);
				forwarder.sendEmptyMessage(null, rst);
				// ignore request
				return null;
			}
		}
	}

	public Exchange receiveResponse(Response response) {
		
		/*
		 * This response could be
		 * - The first CON/NCON/ACK+response => deliver
		 * - Retransmitted CON (because client got no ACK)
		 * 		=> resend ACK
		 */

		KeyMID idByMID = new KeyMID(response.getMID(), 
				response.getSource().getAddress(), response.getSourcePort());
		
		KeyToken idByTok = new KeyToken(response.getToken(), 
				response.getSource().getAddress(), response.getSourcePort());
		
		Exchange exchange = exchangesByToken.get(idByTok);
		
		if (exchange != null) {
			// There is an exchange with the given token
			
			Exchange prev = deduplicator.findPrevious(idByMID, exchange);
			if (prev != null) { // (and thus it holds: prev == exchange)
				LOGGER.fine("Message is a duplicate: "+response);
				response.setDuplicate(true);
			}
			
			if (response.getType() == Type.ACK) { 
				// this is a piggy-backed response and the MID must match
				if (exchange.getCurrentRequest().getMID() == response.getMID()) {
					// The token and MID match. This is a response for this exchange
					return exchange;
					
				} else {
					// The token matches but not the MID. This is a response for an older exchange
					LOGGER.info("Token matches but not MID: wants "+exchange.getCurrentRequest().getMID()+" but gets "+response.getMID());
					EmptyMessage rst = EmptyMessage.newRST(response);
					sendEmptyMessage(exchange, rst);
					// ignore response
					return null;
				}
				
			} else {
				// this is a separate response that we can deliver
				return exchange;
			}
			
		} else {
			// There is no exchange with the given token.

			// This might be a duplicate response to an exchanges that is already completed
			if (response.getType() != Type.ACK) {
				// Need deduplication for CON and NON but not for ACK (because MID defined by server)
				Exchange prev = deduplicator.find(idByMID);
				if (prev != null) { // (and thus it holds: prev == exchange)
					LOGGER.fine("Message is a duplicate, ignore: "+response);
					response.setDuplicate(true);
					return prev;
				}
			}
			
			// This is a totally unexpected response.
			EmptyMessage rst = EmptyMessage.newRST(response);
			sendEmptyMessage(exchange, rst);
			// ignore response
			return null;
		}
	}

	public Exchange receiveEmptyMessage(EmptyMessage message) {
		
		KeyMID idByMID = new KeyMID(message.getMID(),
				message.getSource().getAddress(), message.getSourcePort());
		
		Exchange exchange = exchangesByMID.get(idByMID);
		
		if (exchange != null) {
			return exchange;
		} else {
			LOGGER.info("Matcher received empty message that does not match any exchange: "+message);
			// ignore message;
			return null;
		} // else, this is an ACK for an unknown exchange and we ignore it
	}
	
	public void clear() {
		this.exchangesByMID.clear();
		this.exchangesByToken.clear();
		this.ongoingExchanges.clear();
		deduplicator.clear();
	}
	
	private class ExchangeObserverImpl implements ExchangeObserver {

		@Override
		public void completed(Exchange exchange) {
			if (exchange.getOrigin() == Origin.LOCAL) {
				Request request = exchange.getRequest();
				KeyToken tokKey = new KeyToken(exchange.getToken(),
						request.getDestination().getAddress(), request.getDestinationPort());
				exchangesByToken.remove(tokKey);
				// TODO: What if the request is only a block?
				
				KeyMID midKey = new KeyMID(request.getMID(), 
						request.getDestination().getAddress(), request.getDestinationPort());
				exchangesByMID.remove(midKey);
			}
			if (exchange.getOrigin() == Origin.REMOTE) {
				Request request = exchange.getRequest();
				KeyToken tokKey = new KeyToken(request.getToken(),
						request.getSource().getAddress(), request.getSourcePort());
				ongoingExchanges.remove(tokKey);
				// TODO: What if the request is only a block?
				// TODO: This should only happen if the transfer was blockwise

				Response response = exchange.getResponse();
				KeyMID midKey = new KeyMID(response.getMID(), 
						response.getDestination().getAddress(), response.getDestinationPort());
				exchangesByMID.remove(midKey);
				
			}
		}
		
	}
	
}
