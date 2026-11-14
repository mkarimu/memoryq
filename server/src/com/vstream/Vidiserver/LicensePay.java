package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paying for a licence: Stripe (credit and debit cards - the radio plans' Stripe key), PayPal (Orders API: approve on
 * PayPal, then capture), M-Pesa (Safaricom Daraja STK push: the customer confirms on the phone with the M-Pesa PIN;
 * Safaricom calls back, and the payment is checked again with Safaricom before the licence is marked paid).
 * PayPal and M-Pesa keys are in licence-secrets.txt (Admin -> Licences -> Prices and payments), never shown again.
 */
public final class LicensePay {

    public static final class PayException extends Exception {
        private static final long serialVersionUID = 1L;

        PayException(String m) {
            super(m);
        }
    }

    private final LicenseStore store;
    private final RadioBilling stripe;

    public LicensePay(LicenseStore store, RadioBilling stripe) {
        this.store = store;
        this.stripe = stripe;
    }

    // ---- what is set up ----

    public boolean stripeReady() {
        return stripe != null && stripe.hasKey();
    }

    public boolean paypalReady() {
        return !store.secret("paypal.client").isEmpty() && !store.secret("paypal.secret").isEmpty();
    }

    public boolean mpesaReady() {
        return !store.secret("mpesa.key").isEmpty() && !store.secret("mpesa.secret").isEmpty() && !store.secret("mpesa.shortcode").isEmpty()
                && !store.secret("mpesa.passkey").isEmpty();
    }

    static String amount(long cents) {
        return String.format(Locale.ENGLISH, "%d.%02d", cents / 100, cents % 100);
    }

    // ---- a small HTTP client ----

    static String[] http(String method, String url, Map<String, String> headers, String body) throws PayException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && b.size() < 2000000) {
                    b.write(buf, 0, n);
                }
                in.close();
            }
            return new String[] {String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
        } catch (IOException e) {
            throw new PayException("could not reach the payment service: " + e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static Map<String, Object> json(String[] r, String who) throws PayException {
        try {
            return MiniJson.object(r[1]);
        } catch (RuntimeException e) {
            throw new PayException(who + " gave an answer that could not be read (" + r[0] + ")");
        }
    }

    private static String q(String s) {
        return VideoApi.jq(s == null ? "" : s);
    }

    // ---- Stripe (cards) ----

    /** A Stripe Checkout page for one invoice. Returns its address. */
    public String stripeCheckout(LicenseStore.Invoice inv, String what, String base) throws PayException {
        if (!stripeReady()) {
            throw new PayException("card payments are not set up yet");
        }
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("mode", "payment");
        p.put("success_url", base + "/licence?paid=stripe&invoice=" + inv.id + "&session={CHECKOUT_SESSION_ID}");
        p.put("cancel_url", base + "/licence?cancelled=" + inv.id);
        p.put("client_reference_id", "licence-invoice-" + inv.id);
        p.put("metadata[invoice]", String.valueOf(inv.id));
        p.put("metadata[licence]", String.valueOf(inv.licence));
        p.put("payment_intent_data[metadata][invoice]", String.valueOf(inv.id));
        p.put("line_items[0][quantity]", "1");
        p.put("line_items[0][price_data][currency]", inv.currency);
        p.put("line_items[0][price_data][unit_amount]", String.valueOf(inv.cents));
        p.put("line_items[0][price_data][product_data][name]", what);
        try {
            Map<String, Object> s = stripe.stripe("POST", "/v1/checkout/sessions", p);
            store.setRef(inv.id, MiniJson.str(s, "id"));
            return MiniJson.str(s, "url");
        } catch (RadioBilling.StripeException e) {
            throw new PayException(e.getMessage());
        }
    }

    /** Stripe says the Checkout for this invoice is paid, for its full amount. */
    public boolean stripePaid(LicenseStore.Invoice inv, String session) throws PayException {
        if (session == null || !session.matches("cs_[A-Za-z0-9_]{3,250}")) {
            throw new PayException("that payment reference is not right");
        }
        try {
            Map<String, Object> s = stripe.stripe("GET", "/v1/checkout/sessions/" + session, null);
            return "complete".equals(MiniJson.str(s, "status")) && "paid".equals(MiniJson.str(s, "payment_status"))
                    && String.valueOf(inv.id).equals(MiniJson.str(s, "metadata", "invoice")) && MiniJson.num(s, "amount_total") == inv.cents;
        } catch (RadioBilling.StripeException e) {
            throw new PayException(e.getMessage());
        }
    }

    // ---- PayPal ----

    private String paypalBase() {
        String forced = System.getProperty("vstream.paypalBase");
        if (forced != null) {
            return forced;
        }
        return "live".equals(store.secret("paypal.mode")) ? "https://api-m.paypal.com" : "https://api-m.sandbox.paypal.com";
    }

    private String paypalToken() throws PayException {
        String basic = Base64.getEncoder().encodeToString((store.secret("paypal.client") + ":" + store.secret("paypal.secret")).getBytes(StandardCharsets.UTF_8));
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Basic " + basic);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        String[] r = http("POST", paypalBase() + "/v1/oauth2/token", h, "grant_type=client_credentials");
        Map<String, Object> j = json(r, "PayPal");
        String t = MiniJson.str(j, "access_token");
        if (t.isEmpty()) {
            throw new PayException("PayPal refused the keys (" + MiniJson.str(j, "error_description") + ") - check Admin -> Licences -> Prices and payments");
        }
        return t;
    }

    private Map<String, String> paypalHeaders() throws PayException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer " + paypalToken());
        h.put("Content-Type", "application/json");
        return h;
    }

    /** A PayPal order for one invoice. Returns the address where the customer approves it. */
    public String paypalOrder(LicenseStore.Invoice inv, String what, String base) throws PayException {
        if (!paypalReady()) {
            throw new PayException("PayPal is not set up yet");
        }
        String body = "{\"intent\":\"CAPTURE\",\"purchase_units\":[{\"reference_id\":" + q("invoice-" + inv.id) + ",\"custom_id\":" + q(String.valueOf(inv.id))
                + ",\"description\":" + q(what.length() > 120 ? what.substring(0, 120) : what) + ",\"amount\":{\"currency_code\":" + q(inv.currency.toUpperCase(Locale.ROOT))
                + ",\"value\":" + q(amount(inv.cents)) + "}}],\"application_context\":{\"brand_name\":\"Memoryq\",\"user_action\":\"PAY_NOW\",\"shipping_preference\":\"NO_SHIPPING\","
                + "\"return_url\":" + q(base + "/licence?paid=paypal&invoice=" + inv.id) + ",\"cancel_url\":" + q(base + "/licence?cancelled=" + inv.id) + "}}";
        String[] r = http("POST", paypalBase() + "/v2/checkout/orders", paypalHeaders(), body);
        Map<String, Object> j = json(r, "PayPal");
        String id = MiniJson.str(j, "id");
        if (id.isEmpty()) {
            throw new PayException("PayPal said no: " + MiniJson.str(j, "message"));
        }
        store.setRef(inv.id, id);
        Object links = j.get("links");
        if (links instanceof List) {
            for (Object o : (List<?>) links) {
                String rel = MiniJson.str(o, "rel");
                if ("approve".equals(rel) || "payer-action".equals(rel)) {
                    return MiniJson.str(o, "href");
                }
            }
        }
        throw new PayException("PayPal did not give an approval page");
    }

    /** Takes the money for an approved PayPal order; true when it is captured for the invoice's full amount. */
    public boolean paypalCapture(LicenseStore.Invoice inv, String orderId) throws PayException {
        if (orderId == null || !orderId.matches("[A-Z0-9]{8,40}") || !orderId.equals(inv.ref)) {
            throw new PayException("that PayPal reference is not this payment's");
        }
        Map<String, String> h = paypalHeaders();
        String[] r = http("POST", paypalBase() + "/v2/checkout/orders/" + orderId + "/capture", h, "{}");
        Map<String, Object> j = json(r, "PayPal");
        if (r[1].contains("ORDER_ALREADY_CAPTURED")) {
            j = json(http("GET", paypalBase() + "/v2/checkout/orders/" + orderId, h, null), "PayPal");
        }
        Object cap = MiniJson.at(j, "purchase_units", 0, "payments", "captures", 0);
        return "COMPLETED".equals(MiniJson.str(j, "status")) && cap != null && "COMPLETED".equals(MiniJson.str(cap, "status"))
                && amount(inv.cents).equals(MiniJson.str(cap, "amount", "value"))
                && inv.currency.equalsIgnoreCase(MiniJson.str(cap, "amount", "currency_code"));
    }

    // ---- M-Pesa (Safaricom Daraja) ----

    private String mpesaBase() {
        String forced = System.getProperty("vstream.mpesaBase");
        if (forced != null) {
            return forced;
        }
        return "live".equals(store.secret("mpesa.env")) ? "https://api.safaricom.co.ke" : "https://sandbox.safaricom.co.ke";
    }

    /** 0712345678, +254712345678, 254712345678, 0112345678 -> 2547.../2541...; null if not a Kenyan mobile number. */
    static String phone(String s) {
        String d = s == null ? "" : s.replaceAll("[^0-9]", "");
        if (d.startsWith("0") && d.length() == 10) {
            d = "254" + d.substring(1);
        } else if (d.length() == 9 && (d.startsWith("7") || d.startsWith("1"))) {
            d = "254" + d;
        }
        return d.matches("254[71]\\d{8}") ? d : null;
    }

    /** The price in Kenya shillings (whole shillings, rounded up), at the rate set in Admin. */
    public long kes(long cents) {
        double rate;
        try {
            rate = Double.parseDouble(store.secret("mpesa.rate").isEmpty() ? "129" : store.secret("mpesa.rate"));
        } catch (NumberFormatException e) {
            rate = 129;
        }
        return (long) Math.ceil(cents / 100.0 * rate);
    }

    private String mpesaToken() throws PayException {
        String basic = Base64.getEncoder().encodeToString((store.secret("mpesa.key") + ":" + store.secret("mpesa.secret")).getBytes(StandardCharsets.UTF_8));
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Basic " + basic);
        Map<String, Object> j = json(http("GET", mpesaBase() + "/oauth/v1/generate?grant_type=client_credentials", h, null), "M-Pesa");
        String t = MiniJson.str(j, "access_token");
        if (t.isEmpty()) {
            throw new PayException("Safaricom refused the M-Pesa keys - check Admin -> Licences -> Prices and payments");
        }
        return t;
    }

    private String[] mpesaPassword(long now) {
        String ts = new java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH) {
            {
                setTimeZone(java.util.TimeZone.getTimeZone("Africa/Nairobi"));
            }
        }.format(new java.util.Date(now));
        String sc = store.secret("mpesa.shortcode");
        return new String[] {ts, Base64.getEncoder().encodeToString((sc + store.secret("mpesa.passkey") + ts).getBytes(StandardCharsets.UTF_8))};
    }

    /** Sends the "enter your M-Pesa PIN" request to the customer's phone. */
    public void mpesaPush(LicenseStore.Invoice inv, String phoneNumber, String callbackBase, long now) throws PayException {
        if (!mpesaReady()) {
            throw new PayException("M-Pesa is not set up yet");
        }
        String ph = phone(phoneNumber);
        if (ph == null) {
            throw new PayException("type the Safaricom number that pays, e.g. 0712 345 678");
        }
        String[] pw = mpesaPassword(now);
        boolean till = "till".equals(store.secret("mpesa.type"));
        String partyB = till && !store.secret("mpesa.till").isEmpty() ? store.secret("mpesa.till") : store.secret("mpesa.shortcode");
        String body = "{\"BusinessShortCode\":" + q(store.secret("mpesa.shortcode")) + ",\"Password\":" + q(pw[1]) + ",\"Timestamp\":" + q(pw[0])
                + ",\"TransactionType\":" + q(till ? "CustomerBuyGoodsOnline" : "CustomerPayBillOnline") + ",\"Amount\":" + kes(inv.cents)
                + ",\"PartyA\":" + q(ph) + ",\"PartyB\":" + q(partyB) + ",\"PhoneNumber\":" + q(ph)
                + ",\"CallBackURL\":" + q(callbackBase + "/api/licence/mpesa/callback") + ",\"AccountReference\":" + q("MQ" + inv.licence)
                + ",\"TransactionDesc\":" + q("Memoryq licence") + "}";
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer " + mpesaToken());
        h.put("Content-Type", "application/json");
        Map<String, Object> j = json(http("POST", mpesaBase() + "/mpesa/stkpush/v1/processrequest", h, body), "M-Pesa");
        String id = MiniJson.str(j, "CheckoutRequestID");
        if (!"0".equals(MiniJson.str(j, "ResponseCode")) || id.isEmpty()) {
            String m = MiniJson.str(j, "errorMessage");
            throw new PayException("M-Pesa said no: " + (m.isEmpty() ? MiniJson.str(j, "ResponseDescription") : m));
        }
        store.setRef(inv.id, id);
    }

    /** Asks Safaricom about the push: "paid", "waiting" or why it failed. */
    public String mpesaStatus(LicenseStore.Invoice inv, long now) throws PayException {
        if (inv.ref.isEmpty()) {
            return "waiting";
        }
        String[] pw = mpesaPassword(now);
        String body = "{\"BusinessShortCode\":" + q(store.secret("mpesa.shortcode")) + ",\"Password\":" + q(pw[1]) + ",\"Timestamp\":" + q(pw[0])
                + ",\"CheckoutRequestID\":" + q(inv.ref) + "}";
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer " + mpesaToken());
        h.put("Content-Type", "application/json");
        Map<String, Object> j = json(http("POST", mpesaBase() + "/mpesa/stkpushquery/v1/query", h, body), "M-Pesa");
        String rc = MiniJson.str(j, "ResultCode");
        if ("0".equals(rc)) {
            return "paid";
        }
        if (rc.isEmpty()) {
            return "waiting"; // "The transaction is being processed"
        }
        if ("1032".equals(rc)) {
            return "cancelled on the phone";
        }
        if ("1037".equals(rc)) {
            return "the phone did not answer in time";
        }
        if ("1".equals(rc)) {
            return "not enough money on M-Pesa";
        }
        if ("2001".equals(rc)) {
            return "wrong M-Pesa PIN";
        }
        String d = MiniJson.str(j, "ResultDesc");
        return d.isEmpty() ? "M-Pesa result " + rc : d;
    }

    /** From Safaricom's callback: {CheckoutRequestID, ResultCode, receipt}, or null if it cannot be read. */
    static String[] mpesaCallback(String body) {
        try {
            Map<String, Object> j = MiniJson.object(body);
            Object cb = MiniJson.at(j, "Body", "stkCallback");
            if (cb == null) {
                return null;
            }
            String receipt = "";
            Object items = MiniJson.at(cb, "CallbackMetadata", "Item");
            if (items instanceof List) {
                for (Object o : (List<?>) items) {
                    if ("MpesaReceiptNumber".equals(MiniJson.str(o, "Name"))) {
                        receipt = MiniJson.str(o, "Value");
                    }
                }
            }
            return new String[] {MiniJson.str(cb, "CheckoutRequestID"), MiniJson.str(cb, "ResultCode"), receipt};
        } catch (RuntimeException e) {
            return null;
        }
    }
}