/*************************************************************************\
* Copyright (C) 2010 The Infinite Kind, LLC
*
* This code is released as open source under the Apache 2.0 License:<br/>
* <a href="http://www.apache.org/licenses/LICENSE-2.0">
* http://www.apache.org/licenses/LICENSE-2.0</a><br />
\*************************************************************************/

package com.moneydance.modules.features.yahooqt;

import com.moneydance.apps.md.controller.DateRange;
import com.moneydance.apps.md.model.CurrencyTable;
import com.moneydance.apps.md.model.CurrencyType;
import com.moneydance.apps.md.model.RootAccount;
import com.moneydance.util.CustomDateFormat;

import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.Callable;

/**
 * Downloads price history and current price for securities.
 *
 * @author Kevin Menningen - Mennē Software Solutions, LLC
 */
public class DownloadQuotesTask implements Callable<Boolean> {
  static final String NAME = "Download_Security_Quotes";  // not translatable
  private final StockQuotesModel _model;
  private final ResourceProvider _resources;
  private final CustomDateFormat _dateFormat;
  private final SimpleDateFormat _dateTimeFormat;

  private float _progressPercent = 0.0f;

  DownloadQuotesTask(final StockQuotesModel model, final ResourceProvider resources) {
    _model = model;
    _resources = resources;
    _dateFormat = _model.getPreferences().getShortDateFormatter();
    _dateTimeFormat = new SimpleDateFormat(_dateFormat.getPattern() + " h:mm a");
  }

  @Override
  public String toString() { return NAME; }

  public Boolean call() throws Exception {
    final String taskDisplayName = _resources.getString(L10NStockQuotes.QUOTES);
    // this is a Moneydance string that says 'Downloading {acctname}'
    String format = _model.getGUI().getStr("downloading_acct_x");
    _model.showProgress(0.0f, SQUtil.replaceAll(format, "{acctname}", taskDisplayName));

    RootAccount root = _model.getRootAccount();
    if (root == null) {
      System.err.println("Skipping security prices download, no root account");
      return Boolean.FALSE;
    }
    CurrencyTable ctable = root.getCurrencyTable();
    final int numDays = _model.getHistoryDays();

    boolean success = false;
    int skippedCount = 0, errorCount = 0, successCount = 0;
    try {
      int totalValues = (int) ctable.getCurrencyCount();
      int currIdx = 0;
      for (Enumeration cen = ctable.getAllValues(); cen.hasMoreElements();) {
        CurrencyType currencyType = (CurrencyType) cen.nextElement();
        _progressPercent = currIdx / (float) totalValues;
        if (_progressPercent == 0.0f) {
          _progressPercent = 0.01f;
        }

        if (currencyType.getCurrencyType() == CurrencyType.CURRTYPE_SECURITY) {
          DownloadResult result = updateSecurity(currencyType, numDays);
          if (result.skipped) {
            ++skippedCount;
          } else {
            if (result.currentError || (result.historyErrorCount > 0)) {
              ++errorCount;
            }
            if (!result.currentError || (result.historyRecordCount > 0)) {
              ++successCount;
            }
            // log any messages for those that weren't skipped
            if (!SQUtil.isBlank(result.logMessage)) System.err.println(result.logMessage);
          }
        }
        currIdx++;
      }
      success = true;
    } catch (Exception error) {
      String message = MessageFormat.format(
              _resources.getString(L10NStockQuotes.ERROR_DOWNLOADING_FMT),
              _resources.getString(L10NStockQuotes.QUOTES),
              error.getLocalizedMessage());
      _model.showProgress(0f, message);
      System.err.println(MessageFormat.format("Error while downloading Security Price Quotes: {0}",
              error.getMessage()));
      error.printStackTrace();
      success = false;
    } finally {
      _progressPercent = 0f;
      ctable.fireCurrencyTableModified();
    }

    if (success) {
      SQUtil.pauseTwoSeconds(); // wait a bit so user can read the last price update
      if ((skippedCount == 0) && (errorCount == 0) && (successCount == 0)) {
        String message = MessageFormat.format(
                _resources.getString(L10NStockQuotes.FINISHED_DOWNLOADING_FMT),
                _resources.getString(L10NStockQuotes.QUOTES));
        _model.showProgress(0f, message);
        System.err.println("Finished downloading Security Price Quotes");
      } else {
        String message = MessageFormat.format(
                _resources.getString(L10NStockQuotes.QUOTES_DONE_FMT),
                Integer.toString(skippedCount), Integer.toString(errorCount),
                Integer.toString(successCount));
        _model.showProgress(0f, message);
        System.err.println(MessageFormat.format(
                "Security price update complete with {0} skipped, {1} errors and {2} quotes obtained",
                Integer.toString(skippedCount), Integer.toString(errorCount),
                Integer.toString(successCount)));
      }
    }
    return Boolean.TRUE;
  }


  ///////////////////////////////////////////////////////////////////////////////////////////////
  // Private Methods
  ///////////////////////////////////////////////////////////////////////////////////////////////

  private DownloadResult updateSecurity(CurrencyType currType, int numDays) {
    DateRange dateRange = HistoryDateRange.getRangeForSecurity(currType, numDays);

    // check if the user is skipping this one but leaving the symbol intact
    DownloadResult result = new DownloadResult();
    result.displayName = currType.getName();
    if (!_model.getSymbolMap().getIsCurrencyUsed(currType)) {
      result.skipped = true;
      return result;
    }

    // not skipping, log what we're downloading
    System.err.println("Downloading price of "+currType.getName()+" for dates "+dateRange.format(_dateFormat));
    BaseConnection connection = _model.getSelectedHistoryConnection();
    if (connection == null) {
      final String message = _resources.getString(L10NStockQuotes.ERROR_NO_CONNECTION);
      _model.showProgress(_progressPercent, message);
      result.historyErrorCount = 1;
      result.historyResult = message;
      result.currentError = true;
      result.currentResult = message;
      result.logMessage = "No connection established";
      return result;
    }

    boolean foundPrice = false;
    double latestRate = 0.0;   // the raw downloaded price in terms of the price currency
    long latestPriceDate = 0;
    BaseConnection priceConnection = null;
    // both check for a supporting connection, and also check for 'do not update'
    if (connection.canGetHistory() && _model.isHistoricalPriceSelected()) {
      try {
        final StockHistory history = connection.getHistory(currType, dateRange, true);
        if (history == null) {
          result.skipped = true;
          result.logMessage = "No history obtained for security " + currType.getName();
          return result;
        }
        // we're just counting the number of successful symbols
        if (history.getErrorCount() > 0) result.historyErrorCount = 1;
        if (history.getRecordCount() > 0) {
          result.historyRecordCount = 1;
          result.historyResult = "Success";  // currently not shown to the user
          StockRecord latest = history.findMostRecentValidRecord();
          if (latest != null) {
            foundPrice = true;
            priceConnection = connection;
            latestRate = latest.closeRate;
            // no time conversion needed since historical prices just define date
            latestPriceDate = latest.dateTime;
          }
        } else {
          result.historyResult = "Error";   // currently not shown to the user
          result.logMessage = "No history records returned for security " + currType.getName();
        }
      } catch (DownloadException e) {
        final CurrencyType currency = (e.getCurrency() != null) ? e.getCurrency() : currType;
        String message = MessageFormat.format(
                _resources.getString(L10NStockQuotes.ERROR_HISTORY_FMT),
                currency.getName(), e.getLocalizedMessage());
        _model.showProgress(_progressPercent, message);
        result.historyErrorCount = 1;
        result.historyResult = e.getMessage();
        if (SQUtil.isBlank(result.logMessage)) {
          result.logMessage = MessageFormat.format("Error downloading historical prices for {0}: {1}",
                  currency.getName(), e.getMessage());
        }
      }
    } // if getting price history and connection is not 'do not update'

    // now get the current price
    connection = _model.getSelectedCurrentPriceConnection();
    if ((connection != null) && connection.canGetCurrentPrice() && _model.isCurrentPriceSelected()) {
      try {
        // If we have found a historical price, then we don't save the current price as history.
        // If historical prices were skipped or unable to update, then save the current price as
        // a history entry.
        final boolean autoSaveInHistory = !foundPrice;
        if (autoSaveInHistory) System.err.println("Automatically saving current price of " +
          currType.getName());
        final StockRecord record = connection.getCurrentPrice(currType, autoSaveInHistory);
        if (record == null) {
          result.skipped = true;
          result.logMessage = "No current price obtained for security " + currType.getName();
          return result;
        }
        result.currentError = (record.closeRate == 0.0);
        result.currentResult = record.priceDisplay;
        if (!result.currentError) {
          foundPrice = true;
          // current price download overrides the history download current price
          priceConnection = connection;
          latestRate = record.closeRate;
          latestPriceDate = convertTimeFromExchangeTimeZone(currType, record.dateTime);
        }
      } catch (DownloadException e) {
        final CurrencyType currency = (e.getCurrency() != null) ? e.getCurrency() : currType;
        String message = MessageFormat.format(
                _resources.getString(L10NStockQuotes.ERROR_CURRENT_FMT),
                currency.getName(), e.getLocalizedMessage());
        _model.showProgress(_progressPercent, message);
        result.currentError = true;
        result.currentResult = e.getMessage();
        if (SQUtil.isBlank(result.logMessage)) {
          result.logMessage = MessageFormat.format("Error downloading current price for {0}: {1}",
                  currency.getName(), e.getMessage());
        }
      }
    } // if getting the current price
    
    // update the current price if possible, the last price date is stored as a long
    final String lastUpdateDate = currType.getTag("price_date");
    final long storedCurrentPriceDate = (lastUpdateDate == null) ? 0 : Long.parseLong(lastUpdateDate);
    final boolean currentPriceUpdated = foundPrice && (storedCurrentPriceDate < latestPriceDate);
    final CurrencyType priceCurrency = getPriceCurrency(currType, priceConnection);
    if (currentPriceUpdated) {
      // the user rate should be stored in terms of the base currency, just like the snapshots
      currType.setUserRate(CurrencyTable.convertToBasePrice(latestRate, priceCurrency, latestPriceDate));
      currType.setTag("price_date", String.valueOf(latestPriceDate));
    } else if (foundPrice) {
      // log that we skipped the update and why
      String format = "Current price update time {0} not less than downloaded time {1} for {2}";
      System.err.println(MessageFormat.format(format,
              _dateTimeFormat.format(new Date(storedCurrentPriceDate)),
              _dateTimeFormat.format(new Date(latestPriceDate)),
              result.displayName));
    }
    if (foundPrice) {
      // use whichever connection was last successful at getting the price to show the value
      _model.showProgress(_progressPercent,
                          buildPriceDisplayText(priceCurrency, result.displayName,
                                                latestRate, latestPriceDate));
      if (SQUtil.isBlank(result.logMessage)) {
        result.logMessage = buildPriceLogText(priceCurrency, result.displayName,
                latestRate, latestPriceDate, currentPriceUpdated);
      }
    }
    return result;
  }

  private CurrencyType getPriceCurrency(CurrencyType securityCurrency, BaseConnection priceConnection) {
    if (priceConnection == null) {
      // Essentially an unexpected error condition, but the user may have picked a currency type in
      // the history window that we can use
      String relativeCurrID = securityCurrency.getTag(CurrencyType.TAG_RELATIVE_TO_CURR);
      if(relativeCurrID!=null) {
        return _model.getRootAccount().getCurrencyTable().getCurrencyByIDString(relativeCurrID);
      }
      return _model.getRootAccount().getCurrencyTable().getBaseType(); // punt
    }
    // normal condition - the stock exchange will specify the price currency
    return priceConnection.getPriceCurrency(securityCurrency);
  }

  /**
   * Convert the downloaded price time, which is local to the stock exchange, to the current system
   * time, local to this PC.
   * @param securityCurrency The security that is being updated.
   * @param dateTimeExchange The downloaded time, local to the stock exchange.
   * @return The corrected local time of the stock price update time.
   */
  private long convertTimeFromExchangeTimeZone(CurrencyType securityCurrency, long dateTimeExchange) {
    StockExchange exchange = _model.getSymbolMap().getExchangeForCurrency(securityCurrency);
    if (exchange == null) return dateTimeExchange;
    long exchangeZoneOffsetMs = (long)(exchange.getGMTDiff() * 60 * 60 * 1000);
    Calendar cal = Calendar.getInstance();
    long currentZoneOffsetMs = cal.get(Calendar.ZONE_OFFSET);
    // we wish to correct the exchange date time to local time, so add the difference.
    // Example: current time zone -6 hours = -21600000ms, exchange time zone = -5 = -18000000,
    // correction = -21600000 - -18000000 =  -3600000 (-1 hour)
    return dateTimeExchange + (currentZoneOffsetMs - exchangeZoneOffsetMs);
  }

  private String buildPriceDisplayText(CurrencyType priceCurrency,
                                       String name, double rate, long dateTime) {
    String format = _resources.getString(L10NStockQuotes.SECURITY_PRICE_DISPLAY_FMT);
    long amount = (rate == 0.0) ? 0 : priceCurrency.getLongValue(1.0 / rate);
    final char decimal = _model.getPreferences().getDecimalChar();
    String priceDisplay = priceCurrency.formatFancy(amount, decimal);
    String asofDate =_dateFormat.format(new Date(dateTime));
    return MessageFormat.format(format, name, asofDate, priceDisplay);
  }

  private String buildPriceLogText(CurrencyType priceCurrency,
                                   String name, double rate, long dateTime, boolean updated) {
    String format = updated ? "Current price for {0} as of {1}: {2}" : "Latest historical price for {0} as of {1}: {2}";
    long amount = (rate == 0.0) ? 0 : priceCurrency.getLongValue(1.0 / rate);
    String priceDisplay = priceCurrency.formatFancy(amount, '.');
    final String asofDate;
    if (updated) {
      // the current price can be intra-day, so log the date and time of the price update.
      asofDate = _dateTimeFormat.format(new Date(dateTime));
    } else {
      asofDate = _dateFormat.format(new Date(dateTime));
    }
    return MessageFormat.format(format, name, asofDate, priceDisplay);
  }

}