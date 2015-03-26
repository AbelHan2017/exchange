/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.trade;

import io.bitsquare.arbitration.ArbitrationRepository;
import io.bitsquare.btc.AddressEntry;
import io.bitsquare.btc.BlockChainService;
import io.bitsquare.btc.TradeWalletService;
import io.bitsquare.btc.WalletService;
import io.bitsquare.common.handlers.ErrorMessageHandler;
import io.bitsquare.common.handlers.FaultHandler;
import io.bitsquare.common.handlers.ResultHandler;
import io.bitsquare.crypto.EncryptionService;
import io.bitsquare.crypto.SignatureService;
import io.bitsquare.fiat.FiatAccount;
import io.bitsquare.offer.Offer;
import io.bitsquare.offer.OfferBookService;
import io.bitsquare.p2p.AddressService;
import io.bitsquare.p2p.EncryptedMailboxMessage;
import io.bitsquare.p2p.MailboxMessage;
import io.bitsquare.p2p.MailboxService;
import io.bitsquare.p2p.MessageService;
import io.bitsquare.p2p.Peer;
import io.bitsquare.trade.handlers.TakeOfferResultHandler;
import io.bitsquare.trade.handlers.TransactionResultHandler;
import io.bitsquare.trade.protocol.availability.CheckOfferAvailabilityModel;
import io.bitsquare.trade.protocol.availability.CheckOfferAvailabilityProtocol;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferModel;
import io.bitsquare.trade.protocol.placeoffer.PlaceOfferProtocol;
import io.bitsquare.trade.protocol.trade.messages.TradeMessage;
import io.bitsquare.trade.protocol.trade.offerer.OffererAsBuyerProtocol;
import io.bitsquare.trade.protocol.trade.offerer.models.OffererAsBuyerModel;
import io.bitsquare.trade.protocol.trade.taker.TakerAsSellerProtocol;
import io.bitsquare.trade.protocol.trade.taker.models.TakerAsSellerModel;
import io.bitsquare.user.AccountSettings;
import io.bitsquare.user.User;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.utils.Fiat;

import com.google.common.util.concurrent.FutureCallback;

import java.io.File;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import javafx.collections.ObservableList;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TradeManager {
    private static final Logger log = LoggerFactory.getLogger(TradeManager.class);

    private final User user;
    private final AccountSettings accountSettings;
    private final MessageService messageService;
    private final MailboxService mailboxService;
    private final AddressService addressService;
    private final BlockChainService blockChainService;
    private final WalletService walletService;
    private TradeWalletService tradeWalletService;
    private final SignatureService signatureService;
    private final EncryptionService<MailboxMessage> encryptionService;
    private final OfferBookService offerBookService;
    private final ArbitrationRepository arbitrationRepository;
    private final File storageDir;

    private final Map<String, CheckOfferAvailabilityProtocol> checkOfferAvailabilityProtocolMap = new HashMap<>();

    private final TradeList<OffererTrade> openOfferTrades;
    private final TradeList<Trade> pendingTrades;
    private final TradeList<Trade> closedTrades;
    private boolean shutDownRequested;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TradeManager(User user, AccountSettings accountSettings,
                        MessageService messageService, MailboxService mailboxService, AddressService addressService, BlockChainService blockChainService,
                        WalletService walletService, TradeWalletService tradeWalletService, SignatureService signatureService,
                        EncryptionService<MailboxMessage> encryptionService,
                        OfferBookService offerBookService, ArbitrationRepository arbitrationRepository, @Named("storage.dir") File storageDir) {
        this.user = user;
        this.accountSettings = accountSettings;
        this.messageService = messageService;
        this.mailboxService = mailboxService;
        this.addressService = addressService;
        this.blockChainService = blockChainService;
        this.walletService = walletService;
        this.tradeWalletService = tradeWalletService;
        this.signatureService = signatureService;
        this.encryptionService = encryptionService;
        this.offerBookService = offerBookService;
        this.arbitrationRepository = arbitrationRepository;
        this.storageDir = storageDir;

        this.openOfferTrades = new TradeList<>(storageDir, "OpenOfferTrades");
        this.pendingTrades = new TradeList<>(storageDir, "PendingTrades");
        this.closedTrades = new TradeList<>(storageDir, "ClosedTrades");

        // In case the app did get killed the shutDown from the modules is not called, so we use a shutdown hook
        Thread shutDownHookThread = new Thread(TradeManager.this::shutDown, "TradeManager:ShutDownHook");
        Runtime.getRuntime().addShutdownHook(shutDownHookThread);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////


    public void shutDown() {
        if (!shutDownRequested) {
            log.debug("shutDown");
            shutDownRequested = true;
            // we remove own offers form offerbook when we go offline
            for (OffererTrade offererTrade : openOfferTrades) {
                Offer offer = offererTrade.getOffer();
                offerBookService.removeOfferAtShutDown(offer);
            }
        }
    }

    // When all services are initialized we create the protocols for our open offers and persisted not completed pendingTrades
    // OffererAsBuyerProtocol listens for take offer requests, so we need to instantiate it early.
    public void onAllServicesInitialized() {

        for (OffererTrade offererTrade : openOfferTrades) {
            Offer offer = offererTrade.getOffer();
            // we add own offers to offerbook when we go online again
            offerBookService.addOffer(offer,
                    () -> log.debug("Successful removed open offer from DHT"),
                    (message, throwable) -> log.error("Remove open offer from DHT failed. " + message));

            OffererAsBuyerProtocol protocol = createOffererAsBuyerProtocol(offererTrade);
            offererTrade.setProtocol(protocol);
        }
        for (Trade trade : pendingTrades) {
            // We continue an interrupted trade.
            // TODO if the peer has changed its IP address, we need to make another findPeer request. At the moment we use the peer stored in trade to
            // continue the trade, but that might fail.
            if (trade instanceof OffererTrade) {
                OffererTrade offererTrade = (OffererTrade) trade;
                OffererAsBuyerProtocol protocol = createOffererAsBuyerProtocol(offererTrade);
                offererTrade.setProtocol(protocol);
                offererTrade.updateTxFromWallet(tradeWalletService);
            }
            else if (trade instanceof TakerTrade) {
                TakerTrade takerTrade = (TakerTrade) trade;
                TakerAsSellerProtocol sellerTakesOfferProtocol = createTakerAsSellerProtocol(takerTrade);
                takerTrade.setProtocol(sellerTakesOfferProtocol);
                takerTrade.updateTxFromWallet(tradeWalletService);
            }
        }

        mailboxService.getAllMessages(user.getP2PSigPubKey(),
                (encryptedMailboxMessages) -> {
                    decryptMailboxMessages(encryptedMailboxMessages);
                    emptyMailbox();
                });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void placeOffer(String id,
                           Offer.Direction direction,
                           Fiat price,
                           Coin amount,
                           Coin minAmount,
                           TransactionResultHandler resultHandler,
                           ErrorMessageHandler errorMessageHandler) {

        FiatAccount currentFiatAccount = user.currentFiatAccountProperty().get();
        Offer offer = new Offer(id,
                user.getP2PSigPubKey(),
                direction,
                price.getValue(),
                amount,
                minAmount,
                currentFiatAccount.type,
                currentFiatAccount.currencyCode,
                currentFiatAccount.country,
                currentFiatAccount.id,
                accountSettings.getAcceptedArbitratorIds(),
                accountSettings.getSecurityDeposit(),
                accountSettings.getAcceptedCountries(),
                accountSettings.getAcceptedLanguageLocaleCodes());

        PlaceOfferModel model = new PlaceOfferModel(offer, walletService, offerBookService);

        PlaceOfferProtocol placeOfferProtocol = new PlaceOfferProtocol(
                model,
                (transaction) -> {
                    OffererTrade offererTrade = new OffererTrade(offer);
                    offererTrade.setLifeCycleState(OffererTrade.OffererLifeCycleState.OPEN_OFFER);
                    openOfferTrades.add(offererTrade);

                    OffererAsBuyerProtocol protocol = createOffererAsBuyerProtocol(offererTrade);
                    offererTrade.setProtocol(protocol);
                    resultHandler.handleResult(transaction);
                },
                (message) -> errorMessageHandler.handleErrorMessage(message)
        );

        placeOfferProtocol.placeOffer();
    }

    public void cancelOpenOffer(Offer offer, ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        removeOpenOffer(offer, resultHandler, errorMessageHandler, true);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Take offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void checkOfferAvailability(Offer offer) {
        if (!checkOfferAvailabilityProtocolMap.containsKey(offer.getId())) {
            CheckOfferAvailabilityModel model = new CheckOfferAvailabilityModel(
                    offer,
                    messageService,
                    addressService);

            CheckOfferAvailabilityProtocol protocol = new CheckOfferAvailabilityProtocol(model,
                    () -> disposeCheckOfferAvailabilityRequest(offer),
                    (errorMessage) -> disposeCheckOfferAvailabilityRequest(offer));
            checkOfferAvailabilityProtocolMap.put(offer.getId(), protocol);
            protocol.checkOfferAvailability();
        }
        else {
            log.error("That should never happen: onCheckOfferAvailability already called for offer with ID:" + offer.getId());
        }
    }

    // When closing take offer view, we are not interested in the onCheckOfferAvailability result anymore, so remove from the map
    public void cancelCheckOfferAvailabilityRequest(Offer offer) {
        disposeCheckOfferAvailabilityRequest(offer);
    }

    // First we check if offer is still available then we create the trade with the protocol
    public void requestTakeOffer(Coin amount, Offer offer, TakeOfferResultHandler takeOfferResultHandler) {
        CheckOfferAvailabilityModel model = new CheckOfferAvailabilityModel(offer, messageService, addressService);
        CheckOfferAvailabilityProtocol availabilityProtocol = new CheckOfferAvailabilityProtocol(model,
                () -> {
                    disposeCheckOfferAvailabilityRequest(offer);
                    if (offer.getState() == Offer.State.AVAILABLE) {
                        TakerTrade trade = takeAvailableOffer(amount, offer, model.getPeer());
                        takeOfferResultHandler.handleResult(trade);
                    }
                },
                (errorMessage) -> disposeCheckOfferAvailabilityRequest(offer));
        checkOfferAvailabilityProtocolMap.put(offer.getId(), availabilityProtocol);
        availabilityProtocol.checkOfferAvailability();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void requestWithdraw(String toAddress, Trade trade, ResultHandler resultHandler, FaultHandler faultHandler) {
        AddressEntry addressEntry = walletService.getAddressEntry(trade.getId());
        String fromAddress = addressEntry.getAddressString();

        // TODO handle overpaid securityDeposit
        Coin amountToWithdraw = trade.getSecurityDeposit();
        if (trade instanceof OffererTrade)
            amountToWithdraw = amountToWithdraw.add(((OffererTrade) trade).getTradeAmount());

        FutureCallback<Transaction> callback = new FutureCallback<Transaction>() {
            @Override
            public void onSuccess(@javax.annotation.Nullable Transaction transaction) {
                if (transaction != null) {
                    log.info("onWithdraw onSuccess tx ID:" + transaction.getHashAsString());
                    if (trade instanceof OffererTrade)
                        ((OffererTrade) trade).setLifeCycleState(OffererTrade.OffererLifeCycleState.COMPLETED);
                    else
                        ((TakerTrade) trade).setLifeCycleState(TakerTrade.TakerLifeCycleState.COMPLETED);

                    pendingTrades.remove(trade);
                    closedTrades.add(trade);

                    resultHandler.handleResult();
                }
            }

            @Override
            public void onFailure(@NotNull Throwable t) {
                t.printStackTrace();
                log.error(t.getMessage());
                faultHandler.handleFault("An exception occurred at requestWithdraw (onFailure).", t);
            }
        };
        try {
            walletService.sendFunds(fromAddress, toAddress, amountToWithdraw, callback);
        } catch (AddressFormatException | InsufficientMoneyException e) {
            e.printStackTrace();
            log.error(e.getMessage());
            faultHandler.handleFault("An exception occurred at requestWithdraw.", e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from Offerbook when offer gets removed from DHT
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onOfferRemovedFromRemoteOfferBook(Offer offer) {
        disposeCheckOfferAvailabilityRequest(offer);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ObservableList<OffererTrade> getOpenOfferTrades() {
        return openOfferTrades.getObservableList();
    }

    public ObservableList<Trade> getPendingTrades() {
        return pendingTrades.getObservableList();
    }

    public ObservableList<Trade> getClosedTrades() {
        return closedTrades.getObservableList();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void removeOpenOffer(Offer offer,
                                 ResultHandler resultHandler,
                                 ErrorMessageHandler errorMessageHandler,
                                 boolean isCancelRequest) {
        offerBookService.removeOffer(offer,
                () -> {
                    offer.setState(Offer.State.REMOVED);
                    Optional<OffererTrade> offererTradeOptional = openOfferTrades.stream().filter(e -> e.getId().equals(offer.getId())).findAny();
                    if (offererTradeOptional.isPresent()) {
                        OffererTrade offererTrade = offererTradeOptional.get();
                        openOfferTrades.remove(offererTrade);

                        if (isCancelRequest) {
                            offererTrade.setLifeCycleState(OffererTrade.OffererLifeCycleState.OFFER_CANCELED);
                            closedTrades.add(offererTrade);
                            offererTrade.disposeProtocol();
                        }
                    }

                    disposeCheckOfferAvailabilityRequest(offer);

                    resultHandler.handleResult();
                },
                (message, throwable) -> errorMessageHandler.handleErrorMessage(message));
    }

    private void disposeCheckOfferAvailabilityRequest(Offer offer) {
        if (checkOfferAvailabilityProtocolMap.containsKey(offer.getId())) {
            CheckOfferAvailabilityProtocol protocol = checkOfferAvailabilityProtocolMap.get(offer.getId());
            protocol.cancel();
            checkOfferAvailabilityProtocolMap.remove(offer.getId());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade
    ///////////////////////////////////////////////////////////////////////////////////////////

    private TakerTrade takeAvailableOffer(Coin amount, Offer offer, Peer peer) {
        TakerTrade takerTrade = new TakerTrade(offer, amount, peer);
        takerTrade.setLifeCycleState(TakerTrade.TakerLifeCycleState.PENDING);
        pendingTrades.add(takerTrade);

        TakerAsSellerProtocol sellerTakesOfferProtocol = createTakerAsSellerProtocol(takerTrade);
        takerTrade.setProtocol(sellerTakesOfferProtocol);
        sellerTakesOfferProtocol.takeAvailableOffer();

        return takerTrade;
    }


    private TakerAsSellerProtocol createTakerAsSellerProtocol(TakerTrade takerTrade) {
        TakerAsSellerModel model = new TakerAsSellerModel(
                takerTrade,
                messageService,
                mailboxService,
                walletService,
                blockChainService,
                signatureService,
                arbitrationRepository,
                user,
                storageDir);

        return new TakerAsSellerProtocol(model);
    }


    private OffererAsBuyerProtocol createOffererAsBuyerProtocol(OffererTrade offererTrade) {
        OffererAsBuyerModel model = new OffererAsBuyerModel(
                offererTrade,
                messageService,
                mailboxService,
                walletService,
                blockChainService,
                signatureService,
                arbitrationRepository,
                user,
                storageDir);


        // TODO check, remove listener
        offererTrade.processStateProperty().addListener((ov, oldValue, newValue) -> {
            log.debug("offererTrade state = " + newValue);
            switch (newValue) {
                case DEPOSIT_PUBLISHED:
                    removeOpenOffer(offererTrade.getOffer(),
                            () -> log.debug("remove offer was successful"),
                            (message) -> log.error(message),
                            false);
                    ((OffererTrade) model.trade).setLifeCycleState(OffererTrade.OffererLifeCycleState.PENDING);
                    pendingTrades.add(offererTrade);
                    break;
                default:
                    break;
            }
        });

        return new OffererAsBuyerProtocol(model);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Mailbox
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void decryptMailboxMessages(List<EncryptedMailboxMessage> encryptedMailboxMessages) {
        log.trace("applyMailboxMessage encryptedMailboxMessage.size=" + encryptedMailboxMessages.size());
        for (EncryptedMailboxMessage encrypted : encryptedMailboxMessages) {
            try {
                MailboxMessage mailboxMessage = encryptionService.decryptToObject(user.getP2pEncryptPrivateKey(), encrypted.getBucket());

                if (mailboxMessage instanceof TradeMessage) {
                    String tradeId = ((TradeMessage) mailboxMessage).tradeId;
                    Optional<Trade> tradeOptional = pendingTrades.stream().filter(e -> e.getId().equals(tradeId)).findAny();
                    if (tradeOptional.isPresent())
                        tradeOptional.get().setMailboxMessage(mailboxMessage);
                }
            } catch (Throwable e) {
                e.printStackTrace();
                log.error(e.getMessage());
            }
        }
    }

    private void emptyMailbox() {
        mailboxService.removeAllMessages(user.getP2PSigPubKey(),
                () -> {
                    log.debug("All mailbox entries removed");
                },
                (errorMessage, fault) -> {
                    log.error(errorMessage);
                    log.error(fault.getMessage());
                });
    }
}