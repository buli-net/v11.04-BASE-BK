/*
 * Copyright 2011-2024 Andreas Schildbach and the Bitcoin Wallet contributors
 * Copyright 2024-2026 Buli-Net - v11.04-BASE-BK Fork
 *
 * This file is part of Bitcoin Wallet.
 *
 * Bitcoin Wallet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Bitcoin Wallet. If not, see <https://www.gnu.org/licenses/>.
 *
 * Original source: https://github.com/bitcoin-wallet/bitcoin-wallet
 * Modified: Offline-first + single unified online fallback for all features,
 *           network-aware API, supports all address types (P2PKH/P2SH/P2WPKH/P2WSH/P2TR),
 *           no hardcoded UI text - all via strings.xml
 */

package wallet.ui;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptChunk;
import org.bitcoinj.script.ScriptPattern;
import org.bitcoinj.wallet.Wallet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import wallet.R;
import wallet.WalletApplication;

public class TransactionDetailsActivity extends Activity {

    private static final String TAG = "TxDetails";

    // -----------------------------------------------------------------
    // Configurable API endpoints - change only here
    // -----------------------------------------------------------------
    private static final String API_MAINNET = "https://mempool.space/api/tx/";
    private static final String API_SIGNET = "https://mempool.space/signet/api/tx/";
    private static final String API_TESTNET = "https://mempool.space/testnet/api/tx/";
    private static final String API_CUSTOM = null; // e.g. "https://your-esplora.com/api/tx/"

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS).build();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler ageHandler = new Handler(Looper.getMainLooper());
    private final Runnable ageRunnable = new Runnable() {
        @Override public void run() {
            refreshLiveFields();
            long now = System.currentTimeMillis();
            ageHandler.postDelayed(this, 1000 - (now % 1000));
        }
    };

    // UI references - tv_txid_copy removed
    private TextView tvDirection, tvAmount, tvStatus, tvFee, tvTime, tvHeight, tvMeta, tvTxid, tvAge, tvFrom, tvTo, tvActualFrom, tvActualTo;
    private ImageView ivQr;
    private Bitmap currentQrBitmap;
    private Dialog qrDialog;
    private ImageView qrDialogImageView;

    private Transaction tx;
    private Wallet wallet;
    private NetworkParameters params;

    // Caches for fetched input data
    private final Map<Integer, String> inputAddressCache = new HashMap<>();
    private final Map<Integer, Coin> inputValueCache = new HashMap<>();
    private final Map<Integer, String> inputTypeCache = new HashMap<>();

    /** Offline scan result + flags for what still needs API */
    private static class OfflineData {
        String fromAddrs; boolean needFrom = false;
        String toAddrs;
        Coin fee; boolean needFee = false;
        Integer vsize; boolean needVsize = false;
        Integer weight; boolean needWeight = false;
        Integer confirmations; boolean needConf = false;
        Long blockTime; boolean needTime = false;
        String status; boolean needStatus = false;
    }

    private final TransactionConfidence.Listener confidenceListener = (confidence, reason) ->
            runOnUiThread(() -> refreshLiveFields());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_details);

        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle(R.string.tx_details_title);
        }

        bindViews();

        String txidStr = getIntent().getStringExtra("txid");
        if (txidStr == null) {
            Toast.makeText(this, getString(R.string.tx_details_missing_txid), Toast.LENGTH_SHORT).show();
            finish(); return;
        }

        WalletApplication app = (WalletApplication) getApplication();
        wallet = app.getWallet();
        if (wallet == null) {
            Toast.makeText(this, getString(R.string.tx_details_wallet_not_ready), Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        params = wallet.getNetworkParameters();

        try { tx = wallet.getTransaction(Sha256Hash.wrap(txidStr)); } catch (Exception e) { tx = null; }
        if (tx == null) {
            Toast.makeText(this, getString(R.string.tx_details_transaction_not_found), Toast.LENGTH_SHORT).show();
            finish(); return;
        }

        // Offline-first: direction and amount
        Coin value = Coin.ZERO;
        try { Coin v = tx.getValue(wallet); if (v != null) value = v; } catch (Exception ignored) {}
        boolean isSend = value.isNegative();
        Coin absValue = isSend ? value.negate() : value;

        tvDirection.setText(isSend ? getString(R.string.tx_details_sent) : getString(R.string.tx_details_received));
        String prefix = isSend ? getString(R.string.tx_details_prefix_sent) : getString(R.string.tx_details_prefix_received);
        tvAmount.setText(prefix + absValue.toPlainString() + getString(R.string.tx_details_btc_suffix));
        try {
            tvAmount.setTextColor(getResources().getColor(isSend ? R.color.tx_amount_sent : R.color.tx_amount_recv));
        } catch (Exception ignored) {}

        // Step 1: scan offline and render immediately
        OfflineData offline = scanOffline(tx);
        renderOffline(offline);

        // Step 2: single unified API fallback if anything missing
        if (offline.needFrom || offline.needFee || offline.needVsize || offline.needWeight || offline.needConf || offline.needTime || offline.needStatus) {
            fetchUnifiedFallback(tx.getTxId().toString(), offline);
        }

        setupQr();
        updateLiveQr();
        setupParallaxScroll();
    }

    private void bindViews() {
        tvDirection = findViewById(R.id.tv_direction);
        tvAmount = findViewById(R.id.tv_amount);
        tvStatus = findViewById(R.id.tv_status);
        tvFee = findViewById(R.id.tv_fee);
        tvTime = findViewById(R.id.tv_time);
        tvHeight = findViewById(R.id.tv_height);
        tvMeta = findViewById(R.id.tv_meta);
        tvTxid = findViewById(R.id.tv_txid);
        tvAge = findViewById(R.id.tv_age);
        tvFrom = findViewById(R.id.tv_from);
        tvTo = findViewById(R.id.tv_to);
        tvActualFrom = findViewById(R.id.tv_actual_from);
        tvActualTo = findViewById(R.id.tv_actual_to);
        ivQr = findViewById(R.id.iv_tx_qr);
        if (tvStatus != null) { tvStatus.setGravity(Gravity.END); tvStatus.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvFee != null) { tvFee.setGravity(Gravity.END); tvFee.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvTime != null) { tvTime.setGravity(Gravity.END); tvTime.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvHeight != null) { tvHeight.setGravity(Gravity.END); tvHeight.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvMeta != null) { tvMeta.setGravity(Gravity.END); tvMeta.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvAge != null) { tvAge.setGravity(Gravity.END); tvAge.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
    }

    /** Scan all fields offline first, no network */
    private OfflineData scanOffline(Transaction tx) {
        OfflineData d = new OfflineData();
        // FROM
        List<String> froms = new ArrayList<>();
        boolean missingFrom = false;
        if (tx.getInputs() != null) {
            for (TransactionInput in : tx.getInputs()) {
                String a = getAddressFromInput(in);
                if (a == null) missingFrom = true; else froms.add(a);
            }
        }
        if (missingFrom || froms.isEmpty()) {
            d.needFrom = true;
            d.fromAddrs = getString(R.string.tx_details_fetching);
        } else {
            d.fromAddrs = TextUtils.join("\n", froms);
        }
        // TO - always available offline
        List<String> tos = new ArrayList<>();
        if (tx.getOutputs() != null) {
            for (TransactionOutput out : tx.getOutputs()) {
                String a = getAddressFromScript(out.getScriptPubKey(), params);
                if (a != null) tos.add(a);
            }
        }
        d.toAddrs = TextUtils.join("\n", tos);
        // FEE
        Coin fee = null;
        try { fee = tx.getFee(); } catch (Exception ignored) {}
        if (fee == null) {
            try {
                Coin inSum = Coin.ZERO; boolean ok = true;
                for (TransactionInput in : tx.getInputs()) {
                    TransactionOutput c = getConnectedOutput(in);
                    if (c == null || c.getValue() == null) { ok = false; break; }
                    inSum = inSum.add(c.getValue());
                }
                Coin outSum = Coin.ZERO;
                for (TransactionOutput o : tx.getOutputs()) outSum = outSum.add(o.getValue());
                if (ok && !inSum.isZero() && inSum.isGreaterThan(outSum)) fee = inSum.subtract(outSum);
            } catch (Exception ignored) {}
        }
        if (fee != null && !fee.isNegative()) d.fee = fee; else d.needFee = true;
        // SIZE / WEIGHT
        try { d.vsize = tx.getVsize(); } catch (Exception e) { d.needVsize = true; }
        try { d.weight = tx.getWeight(); } catch (Exception e) { d.needWeight = true; }
        // CONFIRMATIONS / STATUS / TIME
        TransactionConfidence conf = tx.getConfidence();
        if (conf != null) {
            try { d.confirmations = conf.getDepthInBlocks(); } catch (Exception e) { d.needConf = true; }
            try {
                Date t = tx.getUpdateTime();
                if (t != null) d.blockTime = t.getTime(); else d.needTime = true;
            } catch (Exception e) { d.needTime = true; }
            if (d.confirmations != null && d.confirmations > 0) d.status = getString(R.string.tx_details_status_confirmed);
            else d.status = getString(R.string.tx_details_status_pending);
        } else {
            d.needConf = true; d.needTime = true; d.needStatus = true;
        }
        return d;
    }

    private void renderOffline(OfflineData d) {
        if (tvActualFrom != null) tvActualFrom.setText(d.fromAddrs);
        if (tvActualTo != null) tvActualTo.setText(d.toAddrs.isEmpty() ? getString(R.string.tx_details_dash) : d.toAddrs);
        if (tvFee != null) {
            tvFee.setText(d.fee != null ? d.fee.toPlainString() + getString(R.string.tx_details_btc_suffix) : getString(R.string.tx_details_dash));
        }
        refreshLiveFields();
        copyOnClick(tvActualFrom, d.fromAddrs.equals(getString(R.string.tx_details_fetching)) ? "" : d.fromAddrs);
        copyOnClick(tvActualTo, d.toAddrs);
        if (tvTxid != null) { tvTxid.setText(tx.getTxId().toString()); copyOnClick(tvTxid, tx.getTxId().toString()); }
        renderInputsAndOutputs();
    }

    /** Single unified fallback for ALL missing fields */
    private void fetchUnifiedFallback(String txid, OfflineData current) {
        String base = getMempoolBaseUrl();
        if (base == null) return;
        Request req = new Request.Builder().url(base + txid).header("User-Agent", "BuliWallet/11.04").build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { Log.w(TAG, "unified API failed", e); }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                if (!resp.isSuccessful() || resp.body() == null) return;
                String json = resp.body().string();
                long apiFee = extractLong(json, "\"fee\":");
                long apiVsize = extractLong(json, "\"vsize\":");
                long apiWeight = extractLong(json, "\"weight\":");
                long blockTime = extractLong(json, "\"block_time\":");
                boolean confirmed = json.contains("\"confirmed\":true");
                String allFrom = extractAllScriptAddresses(json);
                long blockHeight = extractLong(json, "\"block_height\":");

                mainHandler.post(() -> {
                    if (current.needFrom && allFrom != null) {
                        if (tvActualFrom != null) tvActualFrom.setText(allFrom);
                        copyOnClick(tvActualFrom, allFrom);
                        renderInputsAndOutputsFromApi(json);
                    }
                    if (current.needFee && apiFee >= 0 && tvFee != null) {
                        Coin f = Coin.valueOf(apiFee);
                        tvFee.setText(f.toPlainString() + getString(R.string.tx_details_btc_suffix));
                    }
                    if ((current.needVsize || current.needWeight) && apiVsize > 0 && tvMeta != null) {
                        String meta;
                        if (apiFee >= 0) {
                            long rate = (apiFee * 1000 + apiVsize / 2) / apiVsize;
                            meta = getString(R.string.tx_details_size_format_with_fee_rate, (int)apiVsize, (int)apiWeight, (int)rate, getString(R.string.tx_details_fee_rate_suffix));
                        } else {
                            meta = getString(R.string.tx_details_size_format, (int)apiVsize, (int)apiWeight);
                        }
                        // Append RBF if needed
                        try { if (tx.isOptInFullRBF()) meta += getString(R.string.tx_details_rbf_suffix); } catch (Exception ignored) {}
                        tvMeta.setText(meta);
                    }
                    if (current.needConf || current.needStatus) {
                        if (tvStatus != null) tvStatus.setText(confirmed ? getString(R.string.tx_details_status_confirmed) : getString(R.string.tx_details_status_pending));
                        if (tvHeight != null) {
                            if (confirmed) tvHeight.setText(getString(R.string.tx_details_confirmations_value, 1, (int)blockHeight));
                            else tvHeight.setText(getString(R.string.tx_details_unconfirmed));
                        }
                    }
                    if (current.needTime && blockTime > 0 && tvTime != null) {
                        Date d = new Date(blockTime * 1000);
                        tvTime.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d));
                    }
                    updateLiveQr();
                });
            }
        });
    }

    private void renderInputsAndOutputs() {
        StringBuilder fromSb = new StringBuilder();
        Coin totalFrom = Coin.ZERO;
        int inCount = tx.getInputs() != null ? tx.getInputs().size() : 0;
        for (int i = 0; i < inCount; i++) {
            TransactionInput in = tx.getInputs().get(i);
            Coin v = null;
            String addr = null;
            String type = getString(R.string.tx_details_type_nonstandard);
            try {
                TransactionOutput connected = getConnectedOutput(in);
                if (connected != null) {
                    v = connected.getValue();
                    addr = getAddressFromScript(connected.getScriptPubKey(), params);
                    if (addr == null) addr = getAddressFromScriptSig(in);
                    if (addr == null) addr = getAddressFromWitness(in, params);
                    type = getAddressType(addr, connected.getScriptPubKey());
                } else {
                    addr = getAddressFromScriptSig(in);
                    if (addr != null) type = getAddressType(addr, null);
                    else {
                        addr = getAddressFromWitness(in, params);
                        if (addr != null) type = getAddressType(addr, null);
                        else {
                            if (inputAddressCache.containsKey(i)) {
                                addr = inputAddressCache.get(i);
                                v = inputValueCache.get(i);
                                type = inputTypeCache.get(i);
                            } else {
                                addr = getString(R.string.tx_details_fetching);
                                type = getString(R.string.tx_details_type_p2tr);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (addr == null) addr = getString(R.string.tx_details_unknown);
            if (v != null) totalFrom = totalFrom.add(v);
            else if (inputValueCache.containsKey(i) && inputValueCache.get(i) != null) totalFrom = totalFrom.add(inputValueCache.get(i));
            String amountStr = v != null ? v.toPlainString() + getString(R.string.tx_details_btc_suffix) : (inputValueCache.containsKey(i) ? inputValueCache.get(i).toPlainString() + getString(R.string.tx_details_btc_suffix) : "?" + getString(R.string.tx_details_btc_suffix));
            fromSb.append(getString(R.string.tx_details_io_format, addr, type, amountStr, "")).append("\n");
        }
        String fromText = getString(R.string.tx_details_total_from, totalFrom.toPlainString(), inCount) + "\n" + fromSb.toString().trim();
        StringBuilder toSb = new StringBuilder();
        Coin totalTo = Coin.ZERO;
        int outCount = tx.getOutputs() != null ? tx.getOutputs().size() : 0;
        if (tx.getOutputs() != null) {
            for (TransactionOutput out : tx.getOutputs()) {
                Coin v = out.getValue();
                if (v != null) totalTo = totalTo.add(v);
                String addr = getAddressFromScript(out.getScriptPubKey(), params);
                if (addr == null) addr = getString(R.string.tx_details_unknown);
                String type = getAddressType(addr, out.getScriptPubKey());
                toSb.append(getString(R.string.tx_details_io_format, addr, type, v != null ? v.toPlainString() + getString(R.string.tx_details_btc_suffix) : "?" + getString(R.string.tx_details_btc_suffix), "")).append("\n");
            }
        }
        String toText = getString(R.string.tx_details_total_to, totalTo.toPlainString(), outCount) + "\n" + toSb.toString().trim();
        if (tvFrom != null) { tvFrom.setSingleLine(false); tvFrom.setText(fromText); copyOnClick(tvFrom, fromText); }
        if (tvTo != null) { tvTo.setSingleLine(false); tvTo.setText(toText); copyOnClick(tvTo, toText); }
    }

    private void renderInputsAndOutputsFromApi(String json) {
        // Re-render using API cached addresses
        renderInputsAndOutputs();
    }

    // --- Address helpers ---
    private TransactionOutput getConnectedOutput(TransactionInput in) {
        try {
            TransactionOutPoint outpoint = in.getOutpoint();
            if (outpoint == null) return null;
            TransactionOutput connected = outpoint.getConnectedOutput();
            if (connected != null) return connected;
            if (wallet != null) {
                Transaction prev = wallet.getTransaction(outpoint.getHash());
                if (prev != null) return prev.getOutput((int) outpoint.getIndex());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getAddressFromInput(TransactionInput in) {
        TransactionOutput c = getConnectedOutput(in);
        if (c != null) {
            String a = getAddressFromScript(c.getScriptPubKey(), params);
            if (a != null) return a;
        }
        String a = getAddressFromScriptSig(in);
        if (a != null) return a;
        return getAddressFromWitness(in, params);
    }

    private String getAddressFromScriptSig(TransactionInput in) {
        try {
            Script scriptSig = in.getScriptSig();
            if (scriptSig == null) return null;
            List<ScriptChunk> chunks = scriptSig.getChunks();
            if (chunks == null || chunks.isEmpty()) return null;
            for (int i = chunks.size() - 1; i >= 0; i--) {
                ScriptChunk chunk = chunks.get(i);
                if (chunk.data == null) continue;
                byte[] data = chunk.data;
                if (data.length == 33 || data.length == 65) {
                    try {
                        org.bitcoinj.crypto.ECKey key = org.bitcoinj.crypto.ECKey.fromPublicOnly(data);
                        byte[] hash = key.getPubKeyHash();
                        try {
                            return org.bitcoinj.base.LegacyAddress.fromPubKeyHash(params, hash).toString();
                        } catch (Exception e) {
                            return org.bitcoinj.base.SegwitAddress.fromProgram(params, 0, hash).toString();
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getAddressFromWitness(TransactionInput in, NetworkParameters params) {
        try {
            if (in.getWitness() == null) return null;
            int count = in.getWitness().getPushCount();
            if (count == 0) return null;
            for (int i = count - 1; i >= 0; i--) {
                byte[] push = in.getWitness().getPush(i);
                if (push == null) continue;
                if (push.length == 33 || push.length == 65) {
                    try {
                        org.bitcoinj.crypto.ECKey key = org.bitcoinj.crypto.ECKey.fromPublicOnly(push);
                        byte[] hash = key.getPubKeyHash();
                        return org.bitcoinj.base.SegwitAddress.fromProgram(params, 0, hash).toString();
                    } catch (Exception ignored) {}
                }
                if (push.length == 32) {
                    try {
                        return org.bitcoinj.base.SegwitAddress.fromProgram(params, 1, push).toString();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getAddressFromScript(Script script, NetworkParameters params) {
        if (script == null) return null;
        try { return script.getToAddress(params).toString(); } catch (Exception e) { return null; }
    }

    private String getAddressType(String addr, Script script) {
        try { if (script != null && ScriptPattern.isOpReturn(script)) return getString(R.string.tx_details_type_op_return); } catch (Exception ignored) {}
        if (addr == null) return getString(R.string.tx_details_type_nonstandard);
        if (addr.startsWith("bc1q") || addr.startsWith("tb1q") || addr.startsWith("bcrt1q")) return getString(R.string.tx_details_type_p2wpkh);
        if (addr.startsWith("bc1p") || addr.startsWith("tb1p") || addr.startsWith("bcrt1p")) return getString(R.string.tx_details_type_p2tr);
        if (addr.startsWith("bc1") || addr.startsWith("tb1") || addr.startsWith("bcrt1")) return getString(R.string.tx_details_type_p2wsh);
        if (addr.startsWith("3") || addr.startsWith("2")) return getString(R.string.tx_details_type_p2sh);
        if (addr.startsWith("1") || addr.startsWith("m") || addr.startsWith("n")) return getString(R.string.tx_details_type_p2pkh);
        return getString(R.string.tx_details_type_nonstandard);
    }

    private String getMempoolBaseUrl() {
        if (API_CUSTOM != null && !API_CUSTOM.isEmpty()) return API_CUSTOM.endsWith("/") ? API_CUSTOM : API_CUSTOM + "/";
        try {
            String id = params.getId().toLowerCase(Locale.US);
            if (id.contains("signet")) return API_SIGNET;
            else if (id.contains("test")) return API_TESTNET;
            else if (id.contains("regtest")) return null;
            else return API_MAINNET;
        } catch (Exception e) { return API_MAINNET; }
    }

    // --- Unified parsing helpers - supports all address types ---
    private String extractAllScriptAddresses(String json) {
        List<String> list = new ArrayList<>();
        int idx = 0;
        while ((idx = json.indexOf("scriptpubkey_address", idx)) != -1) {
            int colon = json.indexOf(':', idx);
            if (colon < 0) break;
            int q1 = json.indexOf('"', colon + 1) + 1;
            int q2 = json.indexOf('"', q1);
            if (q1 > 0 && q2 > q1) list.add(json.substring(q1, q2));
            idx = q2;
        }
        return list.isEmpty() ? null : TextUtils.join("\n", list);
    }

    private long extractLong(String json, String key) {
        try {
            int i = json.indexOf(key); if (i < 0) return -1;
            int s = i + key.length();
            int e = s;
            while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
            return Long.parseLong(json.substring(s, e).trim());
        } catch (Exception e) { return -1; }
    }

    private void copyOnClick(TextView tv, String text) {
        if (tv == null) return;
        final String t = text == null ? "" : text;
        if (t.isEmpty() || t.equals(getString(R.string.tx_details_dash)) || t.equals(getString(R.string.tx_details_fetching))) {
            tv.setOnClickListener(null); return;
        }
        tv.setOnClickListener(v -> copy(t));
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("tx", text));
            Toast.makeText(this, getString(R.string.tx_details_copied), Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // --- QR and menu - no hardcoded text ---
    private void setupQr() { if (ivQr != null) ivQr.setOnClickListener(v -> showQrDialog()); }

    private String buildLiveTxText() {
        String ageStr = getTv(tvAge);
        return getString(R.string.qr_direction) + ": " + getTv(tvDirection) + "\n"
                + getString(R.string.qr_amount) + ": " + getTv(tvAmount) + "\n\n"
                + getString(R.string.qr_sender_receiver) + "\n"
                + getString(R.string.qr_from) + ": " + getTv(tvActualFrom) + "\n"
                + getString(R.string.qr_to) + ": " + getTv(tvActualTo) + "\n\n"
                + getString(R.string.qr_tx_details) + "\n"
                + getString(R.string.qr_status) + ": " + getTv(tvStatus) + "\n"
                + getString(R.string.qr_fee) + ": " + getTv(tvFee) + "\n"
                + getString(R.string.qr_size_weight) + ": " + getTv(tvMeta) + "\n"
                + getString(R.string.qr_confirmations) + ": " + getTv(tvHeight) + "\n"
                + getString(R.string.qr_time) + ": " + getTv(tvTime) + "\n"
                + getString(R.string.qr_age) + ": " + ageStr + "\n\n"
                + getString(R.string.qr_sent_details) + "\n" + getTv(tvFrom) + "\n\n"
                + getString(R.string.qr_received_details) + "\n" + getTv(tvTo) + "\n\n"
                + getString(R.string.qr_txid) + "\n" + getTv(tvTxid);
    }

    private String getTv(TextView tv) { return tv != null && tv.getText() != null ? tv.getText().toString() : ""; }

    private void updateLiveQr() {
        try {
            String text = buildLiveTxText();
            if (ivQr != null) { currentQrBitmap = encodeQr(text, 768); ivQr.setImageBitmap(currentQrBitmap); }
            if (qrDialog != null && qrDialog.isShowing() && qrDialogImageView != null) {
                Bitmap big = encodeQr(text, 1024);
                qrDialogImageView.setImageBitmap(big);
                currentQrBitmap = big;
            }
        } catch (Exception e) { Log.e(TAG, "QR update failed", e); }
    }

    private void copyFullTx() { copy(buildLiveTxText()); }

    private void showQrDialog() {
        boolean dark = isDark();
        int bgColor = dark ? Color.BLACK : Color.WHITE;
        int dialogTheme = dark ? android.R.style.Theme_Black_NoTitleBar_Fullscreen : android.R.style.Theme_Light_NoTitleBar_Fullscreen;
        qrDialog = new Dialog(this, dialogTheme);
        qrDialog.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN, android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 21) qrDialog.getWindow().setStatusBarColor(bgColor);
        qrDialog.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        qrDialogImageView = new ImageView(this);
        qrDialogImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrDialogImageView.setPadding(48,48,48,48);
        qrDialogImageView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        qrDialogImageView.setOnClickListener(v -> qrDialog.dismiss());
        root.addView(qrDialogImageView);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(16,24,16,48);
        bar.addView(makeActionButton(android.R.drawable.ic_menu_save, getString(R.string.tx_details_save), dark, v -> saveQrBitmap()));
        bar.addView(makeActionButton(android.R.drawable.ic_menu_share, getString(R.string.tx_details_share), dark, v -> shareTx()));
        bar.addView(makeActionButton(android.R.drawable.ic_menu_search, getString(R.string.tx_details_explore), dark, v -> exploreTx()));
        root.addView(bar);
        qrDialog.setContentView(root);
        qrDialog.setCancelable(true);
        qrDialog.setOnDismissListener(d -> { qrDialog = null; qrDialogImageView = null; });
        qrDialog.show();
        updateLiveQr();
    }

    private LinearLayout makeActionButton(int iconRes, String label, boolean dark, View.OnClickListener onClick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.setClickable(true);
        col.setOnClickListener(onClick);
        col.setPadding(8,8,8,8);
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        int iconSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36, getResources().getDisplayMetrics());
        iv.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        col.addView(iv);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(dark ? 0xFFBBBBBB : 0xFF666666);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0,8,0,0);
        col.addView(tv);
        return col;
    }

    private void saveQrBitmap() {
        try {
            Bitmap bmp = currentQrBitmap;
            if (bmp == null) bmp = encodeQr(buildLiveTxText(), 1024);
            String filename = "tx_" + (tx != null ? tx.getTxId().toString().substring(0,8) : "qr") + "_" + System.currentTimeMillis() + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WalletQR");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) { Toast.makeText(this, getString(R.string.tx_details_save_failed), Toast.LENGTH_SHORT).show(); return; }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, os); }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            }
            Toast.makeText(this, getString(R.string.tx_details_saved_to_pictures), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.tx_details_save_failed_msg, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareTx() {
        try {
            String txid = tx != null ? tx.getTxId().toString() : getTv(tvTxid);
            String shareText = buildLiveTxText() + "\n\nhttps://mempool.space/tx/" + txid;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, shareText);
            startActivity(Intent.createChooser(i, getString(R.string.tx_details_share_tx)));
        } catch (Exception ignored) {}
    }

    private void exploreTx() {
        try {
            String txid = tx != null ? tx.getTxId().toString() : getTv(tvTxid);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://mempool.space/tx/" + txid));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.tx_details_no_browser), Toast.LENGTH_SHORT).show();
        }
    }

    public static Bitmap encodeQr(String text, int size) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int w = bitMatrix.getWidth(); int h = bitMatrix.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
        return bmp;
    }

    private String formatAge(Date txTime) {
        if (txTime == null) return getString(R.string.tx_details_dash);
        java.util.Calendar then = java.util.Calendar.getInstance(); then.setTime(txTime);
        java.util.Calendar now = java.util.Calendar.getInstance();
        int years = now.get(java.util.Calendar.YEAR) - then.get(java.util.Calendar.YEAR);
        int months = now.get(java.util.Calendar.MONTH) - then.get(java.util.Calendar.MONTH);
        int days = now.get(java.util.Calendar.DAY_OF_MONTH) - then.get(java.util.Calendar.DAY_OF_MONTH);
        int hours = now.get(java.util.Calendar.HOUR_OF_DAY) - then.get(java.util.Calendar.HOUR_OF_DAY);
        int minutes = now.get(java.util.Calendar.MINUTE) - then.get(java.util.Calendar.MINUTE);
        int seconds = now.get(java.util.Calendar.SECOND) - then.get(java.util.Calendar.SECOND);
        if (seconds < 0) { seconds += 60; minutes--; }
        if (minutes < 0) { minutes += 60; hours--; }
        if (hours < 0) { hours += 24; days--; }
        if (days < 0) {
            java.util.Calendar temp = (java.util.Calendar) now.clone();
            temp.add(java.util.Calendar.MONTH, -1);
            int daysInLastMonth = temp.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
            days += daysInLastMonth; months--;
        }
        if (months < 0) { months += 12; years--; }
        StringBuilder result = new StringBuilder();
        if (years > 0) result.append(years).append(" ").append(getString(years == 1 ? R.string.qr_year : R.string.qr_years)).append(" ");
        if (months > 0) result.append(months).append(" ").append(getString(months == 1 ? R.string.qr_month : R.string.qr_months)).append(" ");
        if (days > 0) result.append(days).append(" ").append(getString(days == 1 ? R.string.qr_day : R.string.qr_days)).append(" ");
        if (hours > 0 || result.length() > 0) result.append(hours).append(" ").append(getString(hours == 1 ? R.string.qr_hour : R.string.qr_hours)).append(" ");
        if (minutes > 0 || result.length() > 0) result.append(minutes).append(" ").append(getString(minutes == 1 ? R.string.qr_minute : R.string.qr_minutes)).append(" ");
        result.append(seconds).append(" ").append(getString(seconds == 1 ? R.string.qr_second : R.string.qr_seconds)).append(" ");
        result.append(getString(R.string.qr_ago));
        return result.toString();
    }

    private void refreshLiveFields() {
        if (tx == null || tvStatus == null || tvHeight == null) return;
        TransactionConfidence confidence = tx.getConfidence();
        int depth = 0, height = 0;
        if (confidence != null) {
            try { depth = confidence.getDepthInBlocks(); } catch (Exception ignored) {}
            try { height = confidence.getAppearedAtChainHeight(); } catch (Exception ignored) {}
        }
        String statusText;
        int statusColorRes;
        if (depth <= 0) { statusText = getString(R.string.tx_details_status_pending); statusColorRes = R.color.tx_status_pending; }
        else if (depth < 7) { statusText = getString(R.string.tx_details_status_building); statusColorRes = R.color.tx_status_building; }
        else { statusText = getString(R.string.tx_details_status_confirmed); statusColorRes = R.color.tx_status_ok; }
        tvStatus.setText(statusText);
        try { tvStatus.setTextColor(getResources().getColor(statusColorRes)); } catch (Exception ignored) {}
        String confStr = depth <= 0 ? getString(R.string.tx_details_unconfirmed) : getString(R.string.tx_details_confirmations_value, depth, height);
        tvHeight.setText(confStr);
        if (tvAge != null) {
            Date updateTime = null;
            try { updateTime = tx.getUpdateTime(); } catch (Exception ignored) {}
            tvAge.setText(formatAge(updateTime));
        }
        updateLiveQr();
    }

    @Override protected void onResume() {
        super.onResume();
        if (tx != null && tx.getConfidence() != null) tx.getConfidence().addEventListener(confidenceListener);
        refreshLiveFields();
        ageHandler.post(ageRunnable);
    }

    @Override protected void onPause() {
        super.onPause();
        if (tx != null && tx.getConfidence() != null) tx.getConfidence().removeEventListener(confidenceListener);
        ageHandler.removeCallbacks(ageRunnable);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.transaction_details_activity_options, menu);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        final int networkSignificantColor = getResources().getColor(R.color.fg_on_dark_bg_network_significant);
        final View decor = getWindow().getDecorView();
        decor.post(() -> {
            ArrayList<View> actionMenuViews = new ArrayList<>();
            findViewsByClass(decor, "ActionMenuView", actionMenuViews);
            for (View amv : actionMenuViews) {
                if (!(amv instanceof ViewGroup)) continue;
                ViewGroup vg = (ViewGroup) amv;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View itemView = vg.getChildAt(i);
                    if (itemView.getClass().getSimpleName().contains("ActionMenuItemView")) findAndWhiteText(itemView, networkSignificantColor);
                }
            }
        });
        return super.onPrepareOptionsMenu(menu);
    }

    private void findAndWhiteText(View root, int color) {
        if (root instanceof TextView) { ((TextView) root).setTextColor(color); return; }
        if (root instanceof ViewGroup) { ViewGroup vg = (ViewGroup) root; for (int i = 0; i < vg.getChildCount(); i++) findAndWhiteText(vg.getChildAt(i), color); }
    }

    private void findViewsByClass(View root, String className, ArrayList<View> out) {
        if (root.getClass().getSimpleName().contains(className)) out.add(root);
        if (root instanceof ViewGroup) { ViewGroup vg = (ViewGroup) root; for (int i = 0; i < vg.getChildCount(); i++) findViewsByClass(vg.getChildAt(i), className, out); }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        if (item.getItemId() == R.id.transaction_details_options_copy) { copyFullTx(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void setupParallaxScroll() {
        final View scroll = findViewById(R.id.nested_scroll);
        final View cardHeader = findViewById(R.id.card_header);
        if (scroll == null || cardHeader == null) return;
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams layoutParams = new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(cardHeader.getLayoutParams().width, cardHeader.getLayoutParams().height);
        layoutParams.setBehavior(new QuickReturnBehavior());
        cardHeader.setLayoutParams(layoutParams);
        cardHeader.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top;
            int extra = (int) (8 * getResources().getDisplayMetrics().density);
            scroll.setPadding(scroll.getPaddingLeft(), height + extra, scroll.getPaddingRight(), scroll.getPaddingBottom());
        });
    }

    public static final class QuickReturnBehavior extends androidx.coordinatorlayout.widget.CoordinatorLayout.Behavior<View> {
        @Override public boolean onStartNestedScroll(androidx.coordinatorlayout.widget.CoordinatorLayout coordinatorLayout, View child, View directTargetChild, View target, int nestedScrollAxes, int type) {
            return (nestedScrollAxes & androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        }
        @Override public void onNestedScroll(androidx.coordinatorlayout.widget.CoordinatorLayout coordinatorLayout, View child, View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
            float newTrans = child.getTranslationY() - dyConsumed;
            float min = -child.getHeight(); float max = 0;
            if (newTrans < min) newTrans = min; if (newTrans > max) newTrans = max;
            child.setTranslationY(newTrans);
        }
    }
}
