package com.moneydance.modules.features.yahooqt;

import com.moneydance.apps.md.controller.DateRange;
import com.moneydance.apps.md.controller.Util;
import com.moneydance.util.CustomDateFormat;
import com.moneydance.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * q = symbol
 * cid = Company Id
 * startdate = Start date of the historical prices
 * enddate = End date of the historical prices
 * histperiod = weekly or daily history periods
 * start = index on which to display the historical price
 * num = number of historical prices to display (this has some max like 100 or 200)
 * output = output the data in a format (I think it currently supports CSV only)
 * http://www.google.com/finance/historical?q=LON:VOD&startdate=Jun+1%2C+2010&enddate=Jun+19%2C+2010&output=csv
 *
 */
public class GoogleConnection extends BaseConnection {
  // http://finance.google.co.uk/finance/historical?q=LON:VOD&startdate=Oct+1,2008&enddate=Oct+9,2008&output=csv
  private static final String HISTORY_URL_BASE =       "http://www.google.com/finance/historical";
  private final String _displayName;
  static final String PREFS_KEY = "google";
  private final DateFormat _dateFormat;

  public GoogleConnection(StockQuotesModel model, String displayName) {
    super(model);
    _displayName = displayName;
    // example for 6/19/2010 = Jun+19%2C+2010
    _dateFormat = new SimpleDateFormat("MMM+d,+yyyy", Locale.US);
  }

  protected final String getHistoryBaseUrl() { return HISTORY_URL_BASE; }

  @Override
  protected SimpleDateFormat getExpectedDateFormat(boolean getFullHistory) {
    // This is the format returned for June 17, 2010: 17-Jun-10; and June 7, 2010: 7-Jun-10
    return new SimpleDateFormat("d-MMM-yy");
  }

  public String getId() { return PREFS_KEY; }

  @Override
  public String toString() {
    return _displayName;
  }

  public String getFullTickerSymbol(String rawTickerSymbol, StockExchange exchange)
  {
    if (SQUtil.isBlank(rawTickerSymbol)) return null;
    String tickerSymbol = rawTickerSymbol.toUpperCase().trim();
    // check if the exchange was already added on, which will override the selected exchange
    int colonIndex = tickerSymbol.lastIndexOf(':');
    if(colonIndex >= 0) {
      // also check if a currency override suffix was added
      int dashIdx = tickerSymbol.indexOf('-', colonIndex);
      if(dashIdx >= 0) {
        // clip off the currency code but keep the exchange override
        return tickerSymbol.substring(0, dashIdx);
      }
      // keep the exchange override
      return tickerSymbol;
    }
    // Check if the selected exchange has a Google suffix or not. If it does, add it.
    String prefix = exchange.getSymbolGoogle();
    if (SQUtil.isBlank(prefix)) return tickerSymbol;
    return prefix + ":" + tickerSymbol;
  }

  public String getCurrencyCodeForQuote(String rawTickerSymbol, StockExchange exchange)
  {
    if (SQUtil.isBlank(rawTickerSymbol)) return null;
    // check if this symbol overrides the exchange and the currency code
    int periodIdx = rawTickerSymbol.lastIndexOf(':');
    if(periodIdx>0) {
      String marketID = rawTickerSymbol.substring(periodIdx+1);
      if(marketID.indexOf("-")>=0) {
        // the currency ID was encoded along with the market ID
        return StringUtils.fieldIndex(marketID, '-', 1);
      }
    }
    return exchange.getCurrencyCode();
  }

  @Override
  public String getHistoryURL(String fullTickerSymbol, DateRange dateRange) {
    StringBuilder result = new StringBuilder(getHistoryBaseUrl());
    Calendar cal = Calendar.getInstance();
    cal.setTime(Util.convertIntDateToLong(dateRange.getEndDateInt()));
    final Date endDate = cal.getTime();
    cal.add(Calendar.DATE, -dateRange.getNumDays());
    final Date startDate = cal.getTime();

    // encoding the dates appears to break Google, so just leave the commas and plus signs in there
    final String encEndDate = _dateFormat.format(endDate);
    final String encStartDate = _dateFormat.format(startDate);
    String encTicker;
    try {
      encTicker = URLEncoder.encode(fullTickerSymbol, N12EStockQuotes.URL_ENC);
    } catch (UnsupportedEncodingException ignore) {
      // should never happen, as the US-ASCII character set is one that is required to be
      // supported by every Java implementation
      encTicker = fullTickerSymbol;
    }
    // add the parameters
    result.append("?q=");           // symbol
    result.append(encTicker);
    result.append("&startdate=");   // start date
    result.append(encStartDate);
    result.append("&enddate=");     // end date
    result.append(encEndDate);
    result.append("&output=csv");  // output format
    return result.toString();
  }

  public String getCurrentPriceURL(String fullTickerSymbol) {
    // current price apparently not supported by Google, historical only
    return null;
  }

  @Override
  protected String getCurrentPriceHeader() {
    // not supported
    return null;
  }

}