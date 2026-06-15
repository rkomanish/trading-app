package com.niftyautotrader.service.execution;

import com.niftyautotrader.broker.Broker;
import com.niftyautotrader.broker.OrderRequest;
import com.niftyautotrader.broker.OrderResult;
import com.niftyautotrader.model.*;
import com.niftyautotrader.repository.OrderRepository;
import com.niftyautotrader.repository.TradeRepository;
import com.niftyautotrader.service.risk.RiskEngine;
import com.niftyautotrader.service.risk.RiskValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Entry point for placing and managing orders.
 * Every order MUST flow through RiskEngine.validate() — this class enforces it.
 * The RiskEngine field is final; no setter exists. There is no bypass flag.
 */
@Service
public class OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final RiskEngine riskEngine;      // final — cannot be injected away
    private final Broker broker;
    private final OrderRepository orderRepo;
    private final TradeRepository tradeRepo;

    public OrderExecutionService(RiskEngine riskEngine,
                                  Broker broker,
                                  OrderRepository orderRepo,
                                  TradeRepository tradeRepo) {
        this.riskEngine = riskEngine;
        this.broker = broker;
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
    }

    /**
     * Submit a new opening order. Runs risk validation first — always.
     * Returns the persisted Order entity (with final status).
     */
    @Transactional
    public Order submitOpenOrder(OrderRequest request, Signal signal) {
        Order order = createOrder(request, signal.getId());
        transition(order, null, OrderStatus.CREATED, "Order created");

        RiskValidationResult risk = riskEngine.validate(request);
        if (risk.isRejected()) {
            transition(order, OrderStatus.CREATED, OrderStatus.REJECTED,
                risk.firstViolationMessage());
            if (signal != null) {
                signal.setRiskApproved(false);
                signal.setRiskRejectionReason(risk.firstViolationMessage());
            }
            log.warn("Order rejected by RiskEngine: {}", risk.firstViolationMessage());
            return orderRepo.save(order);
        }

        transition(order, OrderStatus.CREATED, OrderStatus.RISK_APPROVED, "Risk approved");
        if (signal != null) signal.setRiskApproved(true);

        OrderResult result = broker.placeOrder(request);
        if (!result.isSuccess()) {
            transition(order, OrderStatus.RISK_APPROVED, OrderStatus.REJECTED, result.getErrorMessage());
            order.setRejectReason(result.getErrorMessage());
            log.error("Broker rejected order: {}", result.getErrorMessage());
            return orderRepo.save(order);
        }

        order.setBrokerOrderId(result.getBrokerOrderId());
        transition(order, OrderStatus.RISK_APPROVED, OrderStatus.SUBMITTED, "Submitted to broker");

        if (result.getStatus() == OrderStatus.FILLED) {
            order.setAvgFillPrice(result.getFillPrice());
            transition(order, OrderStatus.SUBMITTED, OrderStatus.FILLED, "Filled");
            riskEngine.recordTradeApproved();
            log.info("Order FILLED: {} {} @ ₹{}", request.getSymbol(), request.getSide(), result.getFillPrice());
        }

        return orderRepo.save(order);
    }

    /**
     * Submit an exit (closing) order for an existing trade.
     */
    @Transactional
    public Order submitExitOrder(Trade trade, BigDecimal currentPrice, String reason) {
        OrderRequest request = OrderRequest.builder()
            .symbol(trade.getSymbol())
            .side(OrderSide.SELL)
            .quantity(trade.getQuantity())
            .strategyName(trade.getStrategyName())
            .limitPrice(currentPrice)
            .closingOrder(true)
            .build();

        Order order = createOrder(request, null);
        order.setTrade(trade);
        transition(order, null, OrderStatus.CREATED, "Exit order: " + reason);

        RiskValidationResult risk = riskEngine.validate(request);
        if (risk.isRejected()) {
            // Closing order blocked — this means kill switch is active
            transition(order, OrderStatus.CREATED, OrderStatus.REJECTED,
                "Exit blocked: " + risk.firstViolationMessage());
            log.error("EXIT ORDER BLOCKED by risk: {}. MANUAL EXIT REQUIRED for trade {}",
                risk.firstViolationMessage(), trade.getId());
            return orderRepo.save(order);
        }

        transition(order, OrderStatus.CREATED, OrderStatus.RISK_APPROVED, "Risk approved for exit");

        OrderResult result = broker.placeOrder(request);
        if (!result.isSuccess()) {
            log.error("EXIT ORDER FAILED for trade {}. Activating kill switch.", trade.getId());
            riskEngine.activateKillSwitch("Exit order failed for trade " + trade.getId());
            transition(order, OrderStatus.RISK_APPROVED, OrderStatus.REJECTED, result.getErrorMessage());
            return orderRepo.save(order);
        }

        BigDecimal fillPrice = result.getFillPrice() != null ? result.getFillPrice() : currentPrice;
        order.setAvgFillPrice(fillPrice);
        order.setBrokerOrderId(result.getBrokerOrderId());
        transition(order, OrderStatus.RISK_APPROVED, OrderStatus.FILLED, "Exit filled");

        // Compute and record P&L
        BigDecimal pnl = fillPrice.subtract(trade.getEntryPrice())
            .multiply(BigDecimal.valueOf(trade.getQuantity()));
        trade.setExitPrice(fillPrice);
        trade.setExitTime(ZonedDateTime.now(IST));
        trade.setRealizedPnl(pnl);
        trade.setExitReason(reason);
        tradeRepo.save(trade);

        riskEngine.recordRealizedPnl(pnl);
        transition(order, OrderStatus.FILLED, OrderStatus.EXITED, "Trade closed");

        log.info("Trade {} exited: P&L=₹{} reason={}", trade.getId(), pnl, reason);
        return orderRepo.save(order);
    }

    /** Execute kill switch: cancel all open orders and flatten all positions. */
    @Transactional
    public void executeKillSwitch(String reason) {
        riskEngine.activateKillSwitch(reason);
        broker.cancelAllAndFlatten();
        log.error("KILL SWITCH EXECUTED: {}", reason);
    }

    private Order createOrder(OrderRequest request, Long signalId) {
        Order order = new Order();
        order.setSymbol(request.getSymbol());
        order.setSide(request.getSide());
        order.setQuantity(request.getQuantity());
        order.setPrice(request.getLimitPrice());
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(ZonedDateTime.now(IST));
        order.setPaper(broker.isPaper());
        return orderRepo.save(order);
    }

    private void transition(Order order, OrderStatus from, OrderStatus to, String reason) {
        if (from != null) {
            OrderStateTransition t = new OrderStateTransition();
            t.setOrder(order);
            t.setFromStatus(from);
            t.setToStatus(to);
            t.setTransitionedAt(ZonedDateTime.now(IST));
            t.setReason(reason);
            order.getTransitions().add(t);
        }
        order.setStatus(to);
        order.setUpdatedAt(ZonedDateTime.now(IST));
        log.debug("Order {} transition: {} -> {} ({})", order.getId(), from, to, reason);
    }
}
