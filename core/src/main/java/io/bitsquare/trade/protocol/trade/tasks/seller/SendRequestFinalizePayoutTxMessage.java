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

package io.bitsquare.trade.protocol.trade.tasks.seller;

import io.bitsquare.common.taskrunner.TaskRunner;
import io.bitsquare.p2p.listener.SendMessageListener;
import io.bitsquare.trade.OffererTrade;
import io.bitsquare.trade.TakerTrade;
import io.bitsquare.trade.Trade;
import io.bitsquare.trade.protocol.trade.TradeTask;
import io.bitsquare.trade.protocol.trade.messages.RequestFinalizePayoutTxMessage;
import io.bitsquare.trade.states.OffererTradeState;
import io.bitsquare.trade.states.StateUtil;
import io.bitsquare.trade.states.TakerTradeState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SendRequestFinalizePayoutTxMessage extends TradeTask {
    private static final Logger log = LoggerFactory.getLogger(SendRequestFinalizePayoutTxMessage.class);

    public SendRequestFinalizePayoutTxMessage(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void doRun() {
        try {
            RequestFinalizePayoutTxMessage message = new RequestFinalizePayoutTxMessage(
                    processModel.getId(),
                    processModel.getPayoutTxSignature(),
                    processModel.getAddressEntry().getAddressString(),
                    trade.getLockTime()
            );

            processModel.getMessageService().sendEncryptedMessage(
                    trade.getTradingPeer(),
                    processModel.tradingPeer.getPubKeyRing(),
                    message,
                    new SendMessageListener() {
                        @Override
                        public void handleResult() {
                            log.trace("PayoutTxPublishedMessage successfully arrived at peer");

                            if (trade instanceof TakerTrade)
                                trade.setProcessState(TakerTradeState.ProcessState.REQUEST_PAYOUT_FINALIZE_MSG_SENT);
                            else if (trade instanceof OffererTrade)
                                trade.setProcessState(OffererTradeState.ProcessState.REQUEST_PAYOUT_FINALIZE_MSG_SENT);

                            complete();
                        }

                        @Override
                        public void handleFault() {
                            appendToErrorMessage("Sending PayoutTxPublishedMessage failed");
                            trade.setErrorMessage(errorMessage);
                            StateUtil.setSendFailedState(trade);
                            failed();
                        }
                    });
        } catch (Throwable t) {
            t.printStackTrace();
            trade.setThrowable(t);
            failed(t);
        }
    }
}