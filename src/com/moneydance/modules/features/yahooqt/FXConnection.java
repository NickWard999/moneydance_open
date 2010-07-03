package com.moneydance.modules.features.yahooqt;

import com.moneydance.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Hashtable;
import java.util.Vector;


/**
 * Class used to download currency histories via HTTP using the same spreadsheet format as at finance.yahoo.com.
 */
public class FXConnection {
  private static final String CURRENT_BASE_URL = "http://finance.yahoo.com/d/quotes.csv";
  // the rest of it: ?s=USDEUR=X&f=sl1d1t1c1ohgv&e=.csv"

  /**
   * Create a connection object that can retrieve exchange rates
   */
  public FXConnection() {
  }

  /**
   * Retrieve the current information for the given stock ticker symbol.
   */
  public ExchangeRate getCurrentRate(String currencyID, String baseCurrencyID)
      throws Exception {
    currencyID = currencyID.toUpperCase().trim();
    baseCurrencyID = baseCurrencyID.toUpperCase().trim();
    if (currencyID.length() != 3 || baseCurrencyID.length() != 3)
      return null;

    String urlStr = CURRENT_BASE_URL + '?';
    urlStr += "s=" + URLEncoder.encode(baseCurrencyID + currencyID, N12EStockQuotes.URL_ENC) + "=X"; // symbol
    urlStr += "&f=sl1d1t1c1ohgv";  // format of each line
    urlStr += "&e=.csv";  // response format

    URL url = new URL(urlStr);
    BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream(), "ASCII"));
    // read the message...
    double rate = -1;
    while (true) {
      String line = in.readLine();
      if (line == null)
        break;
      line = line.trim();

      String rateStr = StringUtils.fieldIndex(line, ',', 1).trim();

      if (rateStr.length() > 0)
        rate = StringUtils.parseRate(rateStr, '.');
    }
    return new ExchangeRate(currencyID, rate);
  }

  public class ExchangeRate {
    private String currencyID;
    private double rate;

    ExchangeRate(String currencyID, double rate) {
      this.currencyID = currencyID;
      this.rate = rate;
    }

    public String getCurrency() {
      return this.currencyID;
    }

    public double getRate() {
      return this.rate;
    }
  }

  public static void main(String[] args) throws Exception {
    FXConnection fxConnection = new FXConnection();
    FXConnection.ExchangeRate currentRate = fxConnection.getCurrentRate("USD", "EUR");
    System.out.println("rate is " + currentRate.getRate());
  }
}