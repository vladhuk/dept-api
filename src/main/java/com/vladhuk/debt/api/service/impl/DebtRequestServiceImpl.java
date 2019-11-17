package com.vladhuk.debt.api.service.impl;

import com.vladhuk.debt.api.exception.DebtRequestException;
import com.vladhuk.debt.api.exception.ResourceNotFoundException;
import com.vladhuk.debt.api.model.*;
import com.vladhuk.debt.api.repository.DebtRequestRepository;
import com.vladhuk.debt.api.repository.OrderRepository;
import com.vladhuk.debt.api.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.vladhuk.debt.api.model.Status.StatusName.*;

@Service
@Transactional
public class DebtRequestServiceImpl implements DebtRequestService {

    private static final Logger logger = LoggerFactory.getLogger(DebtRequestRepository.class);

    private final DebtRequestRepository debtRequestRepository;
    private final OrderRepository orderRepository;
    private final AuthenticationService authenticationService;
    private final StatusService statusService;
    private final UserService userService;
    private final FriendService friendService;
    private final DebtService debtService;

    public DebtRequestServiceImpl(DebtRequestRepository debtRequestRepository, OrderRepository orderRepository, AuthenticationService authenticationService, StatusService statusService, UserService userService, FriendService friendService, DebtService debtService) {
        this.debtRequestRepository = debtRequestRepository;
        this.orderRepository = orderRepository;
        this.authenticationService = authenticationService;
        this.statusService = statusService;
        this.userService = userService;
        this.friendService = friendService;
        this.debtService = debtService;
    }

    @Override
    public List<DebtRequest> getAllSentDebtRequests() {
        final Long currentUserId = authenticationService.getCurrentUser().getId();
        logger.info("Fetching all sent from user with id {} debt requests", currentUserId);
        return debtRequestRepository.findAllBySenderId(currentUserId);
    }

    @Override
    public List<DebtRequest> getSentDebtRequestsPage(Integer pageNumber, Integer pageSize) {
        final Long currentUserId = authenticationService.getCurrentUser().getId();
        final Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updated_at").descending());

        logger.info("Fetching sent from user with id {} debt requests page", currentUserId);

        return debtRequestRepository.findAllBySenderId(currentUserId, pageable);
    }

    @Override
    public List<DebtRequest> changeOrderStatusToViewed(List<DebtRequest> requests) {
        final Status viewedStatus = statusService.getStatus(VIEWED);

        requests.forEach(request ->
                              request.getOrders()
                                      .stream()
                                      .filter(order -> order.getStatus().getName() == SENT)
                                      .forEach(order -> {
                                          order.setStatus(viewedStatus);
                                          orderRepository.save(order);
                                      })
                );
        return requests;
    }

    @Override
    public List<DebtRequest> getAllReceivedDebtRequests() {
        final Long currentUserId = authenticationService.getCurrentUser().getId();
        logger.info("Fetching all received by user with id {} debt requests", currentUserId);
        return changeOrderStatusToViewed(debtRequestRepository.findAllByReceiverId(currentUserId));
    }

    @Override
    public List<DebtRequest> getReceivedDebtRequestsPage(Integer pageNumber, Integer pageSize) {
        final Long currentUserId = authenticationService.getCurrentUser().getId();
        final Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("updated_at").descending());

        logger.info("Fetching received by user with id {} debt requests page", currentUserId);

        return changeOrderStatusToViewed(debtRequestRepository.findAllByReceiverId(currentUserId, pageable));
    }

    @Override
    public Long countNewReceivedDebtRequests() {
        final Long currentUserId = authenticationService.getCurrentUser().getId();
        final Long statusId = statusService.getStatus(SENT).getId();

        logger.info("Counting new received by user with id {} debt requests", currentUserId);

        return orderRepository.countAllByReceiverIdAndStatusId(currentUserId, statusId);
    }

    @Override
    public DebtRequest sendDebtRequest(DebtRequest debtRequest) {
        final User currentUser = authenticationService.getCurrentUser();
        final List<Order> orders = debtRequest.getOrders()
                .stream()
                .peek(order -> order.setReceiver(userService.getUser(order.getReceiver())))
                .collect(Collectors.toList());

        logger.info("Sending debt request from user {} to users {}", currentUser.getId(), orders.stream().map(order -> order.getReceiver().getId()).collect(Collectors.toList()));

        final Status sentStatus = statusService.getStatus(SENT);

        orders.forEach(order -> {
            if (!friendService.isFriend(order.getReceiver().getId())) {
                logger.error("User {} can not send debt request to user with id {}, because they are not friends", currentUser.getId(), order.getReceiver().getId());
                throw new DebtRequestException("Can not send request because users are not friends");
            }
            order.setReceiver(userService.getUser(order.getReceiver()));
            order.setStatus(sentStatus);
        });

        final DebtRequest requestForSave = new DebtRequest();
        requestForSave.setSender(currentUser);
        requestForSave.setOrders(orders);
        requestForSave.setComment(debtRequest.getComment());
        requestForSave.setStatus(sentStatus);

        return debtRequestRepository.save(requestForSave);
    }

    private Order getViewedReceivedOrderByRequest(Long requestId) {
        final Long currentUserId = authenticationService.getCurrentUser().getId();
        final Long viewedStatusId = statusService.getStatus(VIEWED).getId();
        final Optional<Order> optionalOrder =
                orderRepository.findByDebtRequestIdAndReceiverIdAndStatusId(requestId, currentUserId, viewedStatusId);

        if (optionalOrder.isEmpty()) {
            logger.error("Order in debt request with id {} and status VIEWED with receiverId {} not founded", requestId, currentUserId);
            throw new ResourceNotFoundException("Order in debt request with status VIEWED", "receiverId", currentUserId);
        }

        return optionalOrder.get();
    }

    @Override
    public DebtRequest confirmDebtRequestAndUpdateBalance(Long requestId) {
        logger.info("Confirming debt request with id {}", requestId);

        final Order order = getViewedReceivedOrderByRequest(requestId);

        order.setStatus(statusService.getStatus(CONFIRMED));
        orderRepository.save(order);
        logger.info("Order with id {} is CONFIRMED", order.getId());

        final DebtRequest debtRequest = debtRequestRepository.findById(requestId).get();

        changeStatusToConfirmedIfAllOrdersConfirmed(debtRequest);

        if (debtRequest.getStatus().getName() == CONFIRMED) {
            addToBalances(debtRequest);
        }

        return debtRequestRepository.save(debtRequest);
    }

    @Override
    public void changeStatusToConfirmedIfAllOrdersConfirmed(DebtRequest debtRequest) {
        for (Order order : debtRequest.getOrders()) {
            if (order.getStatus().getName() != CONFIRMED) {
                return;
            }
        }
        debtRequest.setStatus(statusService.getStatus(CONFIRMED));
        logger.info("Debt request with id {} is CONFIRMED", debtRequest.getId());
    }

    private void addToBalances(DebtRequest debtRequest) {
        final User sender = debtRequest.getSender();

        for (Order order: debtRequest.getOrders()) {
            final User receiver = order.getReceiver();

            if (!debtService.isExistDebtWithUsers(sender.getId(), receiver.getId())) {
                debtService.createDebt(new Debt(sender, receiver, order.getAmount()));
            } else {
                final Debt debt = debtService.getDebtWithUsers(sender.getId(), receiver.getId());
                final Float amount = Objects.equals(debt.getCreditor().getId(), sender.getId())
                        ? order.getAmount()
                        : -order.getAmount();
                debtService.addToBalance(debt.getId(), amount);
            }
        }
    }

    @Override
    public DebtRequest rejectDebtRequest(Long requestId) {
        logger.info("Rejecting debt request with id {}", requestId);

        final Status rejectedStatus = statusService.getStatus(REJECTED);

        final Order order = getViewedReceivedOrderByRequest(requestId);
        order.setStatus(rejectedStatus);
        orderRepository.save(order);

        final DebtRequest debtRequest = debtRequestRepository.findById(requestId).get();
        debtRequest.setStatus(rejectedStatus);

        return debtRequestRepository.save(debtRequest);
    }
}
