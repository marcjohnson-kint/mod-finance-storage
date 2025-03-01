package org.folio.service.budget;

import static java.util.stream.Collectors.toList;
import static org.folio.rest.impl.BudgetAPI.BUDGET_TABLE;
import static org.folio.rest.impl.FundAPI.FUND_TABLE;
import static org.folio.rest.persist.HelperUtils.getFullTableName;
import static org.folio.rest.persist.HelperUtils.getQueryValues;
import static org.folio.rest.util.ErrorCodes.GENERIC_ERROR_CODE;
import static org.folio.rest.util.ErrorCodes.NOT_ENOUGH_MONEY_FOR_ALLOCATION;
import static org.folio.rest.util.ErrorCodes.NOT_ENOUGH_MONEY_FOR_TRANSFER;
import static org.folio.rest.util.ResponseUtils.handleFailure;
import static org.folio.rest.util.ResponseUtils.handleNoContentResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.dao.budget.BudgetDAO;
import org.folio.rest.jaxrs.model.Budget;
import org.folio.rest.jaxrs.model.LedgerFiscalYearRollover;
import org.folio.rest.jaxrs.model.Transaction;
import org.folio.rest.persist.CriterionBuilder;
import org.folio.rest.persist.DBClient;
import org.folio.rest.persist.Criteria.Criterion;
import org.folio.rest.util.ErrorCodes;
import org.folio.utils.CalculationUtils;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.sqlclient.Tuple;

public class BudgetService {

  private static final String GROUP_FUND_FY_TABLE = "group_fund_fiscal_year";
  private static final String TRANSACTIONS_TABLE = "transaction";
  public static final String TRANSACTION_IS_PRESENT_BUDGET_DELETE_ERROR = "transactionIsPresentBudgetDeleteError";
  public static final String BUDGET_NOT_FOUND_FOR_TRANSACTION = "Budget not found for pair fiscalYear-fundId";

  public static final String SELECT_BUDGETS_BY_FY_AND_FUND_FOR_UPDATE = "SELECT jsonb FROM %s "
    + "WHERE jsonb->>'fiscalYearId' = $1 AND jsonb->>'fundId' = $2"
    + "FOR UPDATE";

  private final Logger logger = LogManager.getLogger(this.getClass());

  private BudgetDAO budgetDAO;

  public BudgetService(BudgetDAO budgetDAO) {
    this.budgetDAO = budgetDAO;
  }

  public void deleteById(String id, Context vertxContext, Map<String, String> headers,
      Handler<AsyncResult<Response>> asyncResultHandler) {
    vertxContext.runOnContext(v -> {
      DBClient client = new DBClient(vertxContext, headers);
      budgetDAO.getBudgetById(id, client)
        .compose(budget -> checkTransactions(budget, client).map(budget))
        .compose(budget -> client.startTx()
          .compose(t -> unlinkGroupFundFiscalYears(id, client))
          .compose(t -> budgetDAO.deleteBudget(id, client))
          .compose(t -> deleteAllocationTransactions(budget, client))
          .compose(t -> client.endTx())
          .onComplete(reply -> {
            if (reply.failed()) {
              client.rollbackTransaction();
            }
          }))
        .onComplete(handleNoContentResponse(asyncResultHandler, id, "Budget {} {} deleted"));
    });
  }

  private Future<Void> deleteAllocationTransactions(Budget budget, DBClient client) {
    Promise<Void> promise = Promise.promise();

    String sql ="DELETE FROM "+ getFullTableName(client.getTenantId(), TRANSACTIONS_TABLE)
      + " WHERE  (jsonb->>'fromFundId' = '"+ budget.getFundId() +"' OR jsonb->>'toFundId' = '"+ budget.getFundId() + "')"
      + " AND (jsonb->>'fiscalYearId')::text = '" + budget.getFiscalYearId() + "' AND (jsonb->>'transactionType')::text = 'Allocation'";

    client.getPgClient().execute(client.getConnection(), sql, reply -> {
      if (reply.failed()) {
        logger.error("Allocation Transaction deletion by query {} failed", sql, reply.cause());
        handleFailure(promise, reply);
      } else {
        logger.debug("Allocation Transaction successfully deleted");
        promise.complete();
      }
    });
    return promise.future();
  }

  private Future<Void> checkTransactions(Budget budget, DBClient client) {
    Promise<Void> promise = Promise.promise();

    String sql ="SELECT jsonb FROM "+ getFullTableName(client.getTenantId(), TRANSACTIONS_TABLE)
      + " WHERE  (jsonb->>'fromFundId' = '"+ budget.getFundId() + "' AND jsonb->>'fiscalYearId' = '" + budget.getFiscalYearId()
      + "' OR jsonb->>'toFundId' = '"+ budget.getFundId() + "'"
      + " AND jsonb->>'fiscalYearId' = '" + budget.getFiscalYearId() + "') AND ((jsonb->>'transactionType')::text<>'Allocation'"
      + " OR ((jsonb->>'transactionType')::text='Allocation' AND (jsonb->'toFundId') is not null AND (jsonb->'fromFundId') is not null))";

    client.getPgClient().execute(sql, reply -> {
        if (reply.failed()) {
          logger.error("Transaction retrieval by query {} failed", sql, reply.cause());
          handleFailure(promise, reply);
        } else {
          if (reply.result().size() > 0) {
            promise.fail(new HttpException(Response.Status.BAD_REQUEST.getStatusCode(), TRANSACTION_IS_PRESENT_BUDGET_DELETE_ERROR));
          }
          promise.complete();
        }
      });
    return promise.future();
  }

  private Future<Void> unlinkGroupFundFiscalYears(String id, DBClient client) {
    Promise<Void> promise = Promise.promise();

    String sql = "UPDATE " + getFullTableName(client.getTenantId(), GROUP_FUND_FY_TABLE)
        + " SET jsonb = jsonb - 'budgetId' WHERE budgetId=$1;";

    client.getPgClient()
      .execute(client.getConnection(), sql, Tuple.of(UUID.fromString(id)), reply -> {
        if (reply.failed()) {
          logger.error("Failed to update group_fund_fiscal_year by budgetId={}", id, reply.cause());
          handleFailure(promise, reply);
        } else {
          promise.complete();
        }
      });
    return promise.future();
  }

  public Future<Budget> getBudgetByFundIdAndFiscalYearId(String fiscalYearId, String fundId, DBClient dbClient) {
    Promise<Budget> promise = Promise.promise();

    Criterion criterion = new CriterionBuilder().with("fundId", fundId)
      .with("fiscalYearId", fiscalYearId)
      .build();

    budgetDAO.getBudgets(criterion, dbClient)
      .onComplete(reply -> {
        if (reply.failed()) {
          handleFailure(promise, reply);
        } else if (reply.result()
          .isEmpty()) {
          logger.error(BUDGET_NOT_FOUND_FOR_TRANSACTION);
          promise.fail(new HttpException(Response.Status.BAD_REQUEST.getStatusCode(), BUDGET_NOT_FOUND_FOR_TRANSACTION));
        } else {
          promise.complete(reply.result()
            .get(0));
        }
      });

    return promise.future();
  }

  public Future<Budget> getBudgetByFiscalYearIdAndFundIdForUpdate(String fiscalYearId, String fundId, DBClient dbClient) {
    Promise<Budget> promise = Promise.promise();

    String sql = getSelectBudgetQueryByFyAndFundForUpdate(dbClient.getTenantId());

    budgetDAO.getBudgets(sql, Tuple.of(fiscalYearId, fundId), dbClient)
      .onComplete(reply -> {
        if (reply.failed()) {
          handleFailure(promise, reply);
        } else if (reply.result().isEmpty()) {
          logger.error(BUDGET_NOT_FOUND_FOR_TRANSACTION);
          promise.fail(new HttpException(Response.Status.BAD_REQUEST.getStatusCode(), BUDGET_NOT_FOUND_FOR_TRANSACTION));
        } else {
          promise.complete(reply.result().get(0));
        }
      });

    return promise.future();
  }

  public Future<Integer> updateBatchBudgets(Collection<Budget> budgets, DBClient client) {
    budgets.forEach(this::clearReadOnlyFields);
    return budgetDAO.updateBatchBudgets(buildUpdateBudgetsQuery(budgets, client.getTenantId()), client);
  }

  private String buildUpdateBudgetsQuery(Collection<Budget> budgets, String tenantId) {
    List<JsonObject> jsonBudgets = budgets.stream()
      .map(JsonObject::mapFrom)
      .collect(toList());
    return String.format(
        "UPDATE %s AS budgets SET jsonb = b.jsonb FROM (VALUES  %s) AS b (id, jsonb) WHERE b.id::uuid = budgets.id;",
        getFullTableName(tenantId, BUDGET_TABLE), getQueryValues(jsonBudgets));
  }

  public Future<List<Budget>> getBudgets(String sql, Tuple params, DBClient client) {
    return budgetDAO.getBudgets(sql, params, client);
  }

  public void updateBudgetMetadata(Budget budget, Transaction transaction) {
    budget.getMetadata()
      .setUpdatedDate(transaction.getMetadata()
        .getUpdatedDate());
    budget.getMetadata()
      .setUpdatedByUserId(transaction.getMetadata()
        .getUpdatedByUserId());
  }
  public void clearReadOnlyFields(Budget budgetFromNew) {
    budgetFromNew.setAllocated(null);
    budgetFromNew.setAvailable(null);
    budgetFromNew.setUnavailable(null);
    budgetFromNew.setOverEncumbrance(null);
    budgetFromNew.setOverExpended(null);
    budgetFromNew.setCashBalance(null);
    budgetFromNew.setTotalFunding(null);
  }

  public Future<Void> checkBudgetHaveMoneyForTransaction(Transaction transaction, DBClient client) {
    if (transaction.getFromFundId() == null) {
      return Future.succeededFuture();
    }

    return getBudgetByFundIdAndFiscalYearId(transaction.getFiscalYearId(), transaction.getFromFundId(), client).compose(budget -> {
      if (budget.getAvailable() < transaction.getAmount()) {
        ErrorCodes errorCode;
        switch (transaction.getTransactionType()) {
        case TRANSFER:
          errorCode = NOT_ENOUGH_MONEY_FOR_TRANSFER;
          break;
        case ALLOCATION:
          errorCode = NOT_ENOUGH_MONEY_FOR_ALLOCATION;
          break;
        default:
          errorCode = GENERIC_ERROR_CODE;
        }
        logger.error(errorCode.getDescription());
        return Future
          .failedFuture(new HttpException(Response.Status.BAD_REQUEST.getStatusCode(), JsonObject.mapFrom(errorCode.toError())
            .encodePrettily()));
      }
      return Future.succeededFuture();
    });
  }
  public void updateBudgetsWithCalculatedFilds(List<Budget> budgets){
    budgets.forEach(CalculationUtils::calculateBudgetSummaryFields);
  }

  public Future<Void> closeBudgets(LedgerFiscalYearRollover rollover, DBClient client) {
    String sql = String.format(
        "UPDATE %s AS budgets SET jsonb = budgets.jsonb || jsonb_build_object('budgetStatus', 'Closed') "
            + "FROM %s AS fund  WHERE fund.ledgerId::text ='%s' AND budgets.fiscalYearId::text='%s' AND fund.id=budgets.fundId;",
        getFullTableName(client.getTenantId(), BUDGET_TABLE), getFullTableName(client.getTenantId(), FUND_TABLE),
        rollover.getLedgerId(), rollover.getFromFiscalYearId());

    return budgetDAO.updateBatchBudgets(sql, client)
      .map(integer -> null);
  }

  private String getSelectBudgetQueryByFyAndFundForUpdate(String tenantId){
    String budgetTableName = getFullTableName(tenantId, BUDGET_TABLE);
    return String.format(SELECT_BUDGETS_BY_FY_AND_FUND_FOR_UPDATE, budgetTableName);
  }

}
