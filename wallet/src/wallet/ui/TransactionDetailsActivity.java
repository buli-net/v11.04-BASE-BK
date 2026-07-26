/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wallet.ui;

// --- Android framework imports ---
// Core Activity lifecycle, UI widgets, clipboard, media store, intents, graphics
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

// --- ZXing QR library ---
// Used to encode transaction data into QR bitmaps with configurable error correction
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

// --- BitcoinJ core imports ---
// Provides Transaction, Wallet, Address, Script handling
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

// --- Java standard library ---
// Networking for mempool.space, date formatting, collections
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

import wallet.R;
import wallet.WalletApplication;

/**
 * Activity to display detailed information about a single Bitcoin transaction.
 * Features:
 * - Real-time status updates via TransactionConfidence listener
 * - Live age counter ticking every second with real calendar accuracy
 * - Live QR code that updates every second (contains age) and on chain changes
 * - Offline address extraction from scriptSig / witness / connected outputs
 * - Online fallback to mempool.space API for missing input addresses/values
 * - Fee calculation from cached inputs
 */
public class TransactionDetailsActivity extends Activity {

    // --- API endpoints ---
    // Base URLs for mempool.space to fetch previous transaction details when offline data missing
    private static final String API_MAINNET = "https://mempool.space/api/tx/";
    private static final String API_SIGNET = "https://mempool.space/signet/api/tx/";
    private static final String API_TESTNET = "https://mempool.space/testnet/api/tx/";
    private static final String API_CUSTOM = null; // Set to custom URL if user runs own mempool instance

    // --- UI references ---
    // TextViews for header, status, fee, time, meta, age, inputs/outputs, actual from/to
    private TextView tvDirection, tvAmount, tvStatus, tvFee, tvTime, tvHeight, tvMeta, tvTxid;
    private TextView tvAge;
    private TextView tvFrom, tvTo;
    private TextView tvActualFrom, tvActualTo;
    private ImageView ivQr; // Small transparent QR in layout
    private Bitmap currentQrBitmap; // Cached big QR for save/share

    // --- Wallet state ---
    private Transaction tx;
    private Wallet wallet;
    private NetworkParameters params;

    // --- Confidence listener ---
    // Triggered when transaction gets new confirmation / block appearance / depth changes
    // Calls refreshLiveFields() to update status, height, age, and QR live
    private final TransactionConfidence.Listener confidenceListener = new TransactionConfidence.Listener() {
        @Override
        public void onConfidenceChanged(TransactionConfidence confidence, ChangeReason reason) {
            runOnUiThread(() -> refreshLiveFields());
        }
    };

    // --- QR dialog ---
    // Fullscreen dialog showing big QR, with save/share/explore actions
    private Dialog qrDialog;
    private ImageView qrDialogImageView;

    // --- Handlers for live updates ---
    private final Handler mainHandler = new Handler(Looper.getMainLooper()); // UI thread posts from background
    private final Handler ageHandler = new Handler(Looper.getMainLooper()); // Age ticker
    // Runnable that fires every second, aligned to wall-clock second boundary (1000 - now%1000)
    private final Runnable ageRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLiveFields(); // Updates age text and QR
            long now = System.currentTimeMillis();
            ageHandler.postDelayed(this, 1000 - (now % 1000)); // Schedule next tick exactly on next second
        }
    };

    // --- Caches for fetched inputs ---
    // Map input index -> address / value / type fetched from mempool.space
    private final Map<Integer, String> inputAddressCache = new HashMap<>();
    private final Map<Integer, Coin> inputValueCache = new HashMap<>();
    private final Map<Integer, String> inputTypeCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Inflate layout containing cards: header, sender/receiver, tx details, io, txid, qr
        setContentView(R.layout.activity_transaction_details);

        // --- ActionBar setup ---
        ActionBar ab = getActionBar();
        if (ab!= null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setTitle(R.string.tx_details_title);
        }

        // --- Find all views ---
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

        // --- Right-align detail fields for consistent column layout ---
        if (tvStatus!= null) { tvStatus.setGravity(Gravity.END); tvStatus.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvFee!= null) { tvFee.setGravity(Gravity.END); tvFee.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvTime!= null) { tvTime.setGravity(Gravity.END); tvTime.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvHeight!= null) { tvHeight.setGravity(Gravity.END); tvHeight.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvMeta!= null) { tvMeta.setGravity(Gravity.END); tvMeta.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }
        if (tvAge!= null) { tvAge.setGravity(Gravity.END); tvAge.setTextAlignment(TextView.TEXT_ALIGNMENT_VIEW_END); }

        // --- Get txid from intent ---
        String txidStr = getIntent().getStringExtra("txid");
        if (txidStr == null) {
            Toast.makeText(this, getString(R.string.tx_details_missing_txid), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // --- Load wallet ---
        WalletApplication app = (WalletApplication) getApplication();
        wallet = app.getWallet();
        if (wallet == null) {
            Toast.makeText(this, getString(R.string.tx_details_wallet_not_ready), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        params = wallet.getNetworkParameters();

        // --- Load transaction from wallet by hash ---
        try {
            tx = wallet.getTransaction(Sha256Hash.wrap(txidStr));
        } catch (Exception e) {
            tx = null;
        }
        if (tx == null) {
            Toast.makeText(this, getString(R.string.tx_details_transaction_not_found), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // --- Determine direction (sent/received) and amount ---
        Coin value = Coin.ZERO;
        try {
            Coin v = tx.getValue(wallet);
            if (v!= null) value = v;
        } catch (Exception ignored) {}
        boolean isSend = value.isNegative();
        Coin absValue = isSend? value.negate() : value;

        tvDirection.setText(isSend? getString(R.string.tx_details_sent) : getString(R.string.tx_details_received));
        tvAmount.setText((isSend? "-" : "+") + absValue.toPlainString() + getString(R.string.tx_details_btc_suffix));
        try {
            tvAmount.setTextColor(getResources().getColor(isSend? R.color.tx_amount_sent : R.color.tx_amount_recv));
        } catch (Exception ignored) {}

        // Initial live fields refresh (status, confirmations, age, QR)
        refreshLiveFields();

        // --- Fee display (may be null if not all inputs known) ---
        Coin fee = null;
        try { fee = tx.getFee(); } catch (Exception ignored) {}
        tvFee.setText(fee!= null? fee.toPlainString() + getString(R.string.tx_details_btc_suffix) : getString(R.string.tx_details_dash));

        // --- Time display ---
        Date updateTime = null;
        try { updateTime = tx.getUpdateTime(); } catch (Exception ignored) {}
        tvTime.setText(updateTime!= null? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(updateTime) : getString(R.string.tx_details_dash));

        // --- Size / weight / RBF / fee rate ---
        int size = 0, weight = 0;
        boolean rbf = false;
        try { size = tx.getMessageSize(); } catch (Exception ignored) {}
        try { weight = tx.getWeight(); } catch (Exception ignored) {}
        try { rbf = tx.isOptInFullRBF(); } catch (Exception ignored) {}
        String feeRate = "";
        if (fee!= null && weight > 0) {
            try {
                long satPerVbyte = fee.getValue() * 4 / weight;
                feeRate = " · " + satPerVbyte + getString(R.string.tx_details_fee_rate_suffix);
            } catch (Exception ignored) {}
        }
        tvMeta.setText(size + " bytes · " + weight + " wu" + feeRate + (rbf? " · RBF" : ""));

        // --- Resolve actual from/to for header (mine vs foreign) ---
        String actualFrom = null;
        String actualTo = null;
        try {
            if (isSend) {
                actualTo = getOutputAddress(tx, params, wallet, false);
                actualFrom = getInputAddressOffline(tx, params, wallet, true);
            } else {
                actualFrom = getInputAddressOffline(tx, params, wallet, false);
                actualTo = getOutputAddress(tx, params, wallet, true);
            }
            if (actualFrom == null) actualFrom = getInputAddressOffline(tx, params, wallet, null);
            if (actualTo == null) actualTo = getOutputAddress(tx, params, wallet, null);
        } catch (Exception ignored) {}

        boolean isFetchingFrom = false;
        if (actualFrom == null) {
            if (!isSend && tx.getInputs()!= null &&!tx.getInputs().isEmpty()) {
                actualFrom = getString(R.string.tx_details_fetching);
                isFetchingFrom = true;
            } else {
                actualFrom = getString(R.string.tx_details_dash);
            }
        }
        if (actualTo == null) actualTo = getString(R.string.tx_details_dash);

        tvActualFrom.setText(actualFrom);
        tvActualTo.setText(actualTo);
        copyOnClick(tvActualFrom, actualFrom.equals(getString(R.string.tx_details_fetching))? "" : actualFrom);
        copyOnClick(tvActualTo, actualTo);

        // If sender unknown, fetch first input from mempool.space
        if (isFetchingFrom) {
            TransactionInput firstIn = tx.getInputs().get(0);
            fetchSenderFromMempool(firstIn.getOutpoint().getHash().toString(), (int) firstIn.getOutpoint().getIndex(), 0, true);
        }

        // Render full input/output lists
        renderInputsAndOutputs();

        // --- TxID display ---
        String hash = tx.getTxId().toString();
        tvTxid.setText(hash);
        copyOnClick(tvTxid, hash);

        // --- QR, parallax, expandable cards ---
        setupQr();
        updateLiveQr();
        setupParallaxScroll();
        setupExpandableCards();
    }

    /**
     * Render inputs and outputs section.
     * For each input: try to get connected output (offline), else scriptSig/witness, else cached mempool data, else fetch.
     * Calculates total from/to for header summary.
     */
    private void renderInputsAndOutputs() {
        StringBuilder fromSb = new StringBuilder();
        Coin totalFrom = Coin.ZERO;
        int inCount = tx.getInputs()!= null? tx.getInputs().size() : 0;

        for (int i = 0; i < inCount; i++) {
            TransactionInput in = tx.getInputs().get(i);
            Coin v = null;
            String addr = null;
            String type = getString(R.string.tx_details_type_nonstandard);

            try {
                TransactionOutput connected = getConnectedOutput(in);
                if (connected!= null) {
                    v = connected.getValue();
                    addr = getAddressFromScript(connected.getScriptPubKey(), params);
                    if (addr == null) addr = getAddressFromScriptSig(in);
                    if (addr == null) addr = getAddressFromWitness(in, params);
                    type = getAddressType(addr, connected.getScriptPubKey());
                } else {
                    addr = getAddressFromScriptSig(in);
                    if (addr!= null) {
                        type = getAddressType(addr, null);
                    } else {
                        addr = getAddressFromWitness(in, params);
                        if (addr!= null) {
                            type = getAddressType(addr, null);
                        } else {
                            try {
                                byte[] connectedBytes = in.getOutpoint().getConnectedPubKeyScript();
                                if (connectedBytes!= null && connectedBytes.length > 0) {
                                    Script s = new Script(connectedBytes);
                                    addr = getAddressFromScript(s, params);
                                    if (addr!= null) type = getAddressType(addr, s);
                                }
                            } catch (Exception ignored2) {}

                            if (addr == null) {
                                if (inputAddressCache.containsKey(i)) {
                                    addr = inputAddressCache.get(i);
                                    v = inputValueCache.get(i);
                                    type = inputTypeCache.get(i);
                                } else {
                                    addr = getString(R.string.tx_details_fetching);
                                    type = getString(R.string.tx_details_type_p2tr);
                                    fetchSenderFromMempool(in.getOutpoint().getHash().toString(), (int) in.getOutpoint().getIndex(), i, false);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (addr == null) addr = getString(R.string.tx_details_unknown);
            if (v!= null) totalFrom = totalFrom.add(v);
            else if (inputValueCache.containsKey(i) && inputValueCache.get(i)!= null) {
                totalFrom = totalFrom.add(inputValueCache.get(i));
            }

            String amountStr = v!= null? v.toPlainString() + getString(R.string.tx_details_btc_suffix) : (inputValueCache.containsKey(i)? inputValueCache.get(i).toPlainString() + getString(R.string.tx_details_btc_suffix) : "?" + getString(R.string.tx_details_btc_suffix));
            fromSb.append(addr).append(" (").append(type).append(") - ").append(amountStr).append("\n");
        }

        String fromText = getString(R.string.tx_details_total_from, totalFrom.toPlainString(), inCount) + "\n" + fromSb.toString().trim();

        StringBuilder toSb = new StringBuilder();
        Coin totalTo = Coin.ZERO;
        int outCount = tx.getOutputs()!= null? tx.getOutputs().size() : 0;
        if (tx.getOutputs()!= null) {
            for (TransactionOutput out : tx.getOutputs()) {
                Coin v = out.getValue();
                if (v!= null) totalTo = totalTo.add(v);
                String addr = getAddressFromScript(out.getScriptPubKey(), params);
                if (addr == null) addr = getString(R.string.tx_details_unknown);
                String type = getAddressType(addr, out.getScriptPubKey());
                toSb.append(addr).append(" (").append(type).append(") - ").append(v!= null? v.toPlainString() + getString(R.string.tx_details_btc_suffix) : "?" + getString(R.string.tx_details_btc_suffix)).append("\n");
            }
        }

        String toText = getString(R.string.tx_details_total_to, totalTo.toPlainString(), outCount) + "\n" + toSb.toString().trim();

        tvFrom.setSingleLine(false);
        tvTo.setSingleLine(false);
        tvFrom.setText(fromText);
        tvTo.setText(toText);
        copyOnClick(tvFrom, fromText);
        copyOnClick(tvTo, toText);

        tryUpdateFee();
    }

    @Override protected void onResume() {
        super.onResume();
        // Register confidence listener for live status updates
        if (tx!= null && tx.getConfidence()!= null) tx.getConfidence().addEventListener(confidenceListener);
        refreshLiveFields();
        ageHandler.post(ageRunnable); // Start second ticker
    }

    @Override protected void onPause() {
        super.onPause();
        // Unregister to avoid leaks
        if (tx!= null && tx.getConfidence()!= null) tx.getConfidence().removeEventListener(confidenceListener);
        ageHandler.removeCallbacks(ageRunnable); // Stop ticker
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.transaction_details_activity_options, menu);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        // Tint action menu text to network significant color (post to decor view to ensure inflated)
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
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) findAndWhiteText(vg.getChildAt(i), color);
        }
    }

    private void findViewsByClass(View root, String className, ArrayList<View> out) {
        if (root.getClass().getSimpleName().contains(className)) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) findViewsByClass(vg.getChildAt(i), className, out);
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        if (item.getItemId() == R.id.transaction_details_options_copy) { copyFullTx(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // --- Wallet helper: get connected output if wallet knows previous tx ---
    private TransactionOutput getConnectedOutput(TransactionInput in) {
        try {
            TransactionOutPoint outpoint = in.getOutpoint();
            if (outpoint == null) return null;
            TransactionOutput connected = outpoint.getConnectedOutput();
            if (connected!= null) return connected;
            if (wallet!= null) {
                Transaction prev = wallet.getTransaction(outpoint.getHash());
                if (prev!= null) return prev.getOutput((int) outpoint.getIndex());
            }
        } catch (Exception ignored) {}
        return null;
    }

    // --- Extract address from scriptSig (legacy P2PKH) by looking for pubkey push ---
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

    // --- Extract address from witness (P2WPKH / P2TR) ---
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

    // --- Convert Script to address string using network params ---
    private String getAddressFromScript(Script script, NetworkParameters params) {
        if (script == null) return null;
        try { return script.getToAddress(params).toString(); } catch (Exception e) { return null; }
    }

    // --- Determine address type label for UI (P2PKH, P2SH, P2WPKH, P2TR, OP_RETURN, etc.) ---
    private String getAddressType(String addr, Script script) {
        try {
            if (script!= null && ScriptPattern.isOpReturn(script)) return getString(R.string.tx_details_type_op_return);
        } catch (Exception ignored) {}
        if (addr == null) return getString(R.string.tx_details_type_nonstandard);
        if (addr.startsWith("bc1q") || addr.startsWith("tb1q") || addr.startsWith("bcrt1q")) return getString(R.string.tx_details_type_p2wpkh);
        if (addr.startsWith("bc1p") || addr.startsWith("tb1p") || addr.startsWith("bcrt1p")) return getString(R.string.tx_details_type_p2tr);
        if (addr.startsWith("bc1") || addr.startsWith("tb1") || addr.startsWith("bcrt1")) return getString(R.string.tx_details_type_p2wsh);
        if (addr.startsWith("3") || addr.startsWith("2")) return getString(R.string.tx_details_type_p2sh);
        if (addr.startsWith("1") || addr.startsWith("m") || addr.startsWith("n")) return getString(R.string.tx_details_type_p2pkh);
        return getString(R.string.tx_details_type_nonstandard);
    }

    // --- Offline input address resolution iterating over inputs, optionally filtering mine vs foreign ---
    private String getInputAddressOffline(Transaction tx, NetworkParameters params, Wallet wallet, Boolean mineOnly) {
        if (tx.getInputs() == null) return null;
        for (TransactionInput in : tx.getInputs()) {
            try {
                TransactionOutput connected = getConnectedOutput(in);
                if (connected!= null) {
                    if (mineOnly!= null) {
                        boolean isMine;
                        try { isMine = connected.isMine(wallet); } catch (Exception e) { continue; }
                        if (isMine!= mineOnly) continue;
                    }
                    String a = getAddressFromScript(connected.getScriptPubKey(), params);
                    if (a == null) a = getAddressFromScriptSig(in);
                    if (a == null) a = getAddressFromWitness(in, params);
                    if (a!= null) return a;
                } else {
                    if (mineOnly == null ||!mineOnly) {
                        String a = getAddressFromScriptSig(in);
                        if (a == null) a = getAddressFromWitness(in, params);
                        if (a == null) {
                            try {
                                byte[] connectedBytes = in.getOutpoint().getConnectedPubKeyScript();
                                if (connectedBytes!= null && connectedBytes.length > 0) {
                                    Script s = new Script(connectedBytes);
                                    a = getAddressFromScript(s, params);
                                }
                            } catch (Exception ignored2) {}
                        }
                        if (a!= null) return a;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // --- Offline output address resolution ---
    private String getOutputAddress(Transaction tx, NetworkParameters params, Wallet wallet, Boolean mineOnly) {
        if (tx.getOutputs() == null) return null;
        for (TransactionOutput out : tx.getOutputs()) {
            try {
                if (mineOnly!= null) {
                    boolean isMine;
                    try { isMine = out.isMine(wallet); } catch (Exception e) { continue; }
                    if (isMine!= mineOnly) continue;
                }
                String a = getAddressFromScript(out.getScriptPubKey(), params);
                if (a!= null) return a;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // --- Choose mempool API base URL depending on network (mainnet/testnet/signet/regtest/custom) ---
    private String getMempoolBaseUrl() {
        if (API_CUSTOM!= null &&!API_CUSTOM.isEmpty()) {
            return API_CUSTOM.endsWith("/")? API_CUSTOM : API_CUSTOM + "/";
        }
        try {
            String id = params.getId().toLowerCase(Locale.US);
            if (id.contains("signet")) return API_SIGNET;
            else if (id.contains("test")) return API_TESTNET;
            else if (id.contains("regtest")) return null;
            else return API_MAINNET;
        } catch (Exception e) { return API_MAINNET; }
    }

    /**
     * Fetch previous tx from mempool.space API to obtain input address and value.
     * Runs on background thread, parses JSON manually (no Gson to keep APK small), caches result, posts UI update.
     */
    private void fetchSenderFromMempool(String prevTxId, int voutIndex, int inputPos, boolean updateActualFrom) {
        String base = getMempoolBaseUrl();
        if (base == null) return;
        if (inputAddressCache.containsKey(inputPos)) return;
        new Thread(() -> {
            try {
                URL url = new URL(base + prevTxId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "BuliWallet/11.04");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode()!= 200) return;
                InputStream is = conn.getInputStream();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                is.close();

                int voutStart = body.indexOf("\"vout\":[");
                if (voutStart == -1) return;
                String voutSection = body.substring(voutStart);
                String[] vouts = voutSection.split("\\{\"scriptpubkey");
                if (voutIndex + 1 >= vouts.length) return;
                String targetVout = vouts[voutIndex + 1];

                String addrMarker = "\"scriptpubkey_address\":\"";
                int addrIdx = targetVout.indexOf(addrMarker);
                if (addrIdx == -1) return;
                int addrStart = addrIdx + addrMarker.length();
                int addrEnd = targetVout.indexOf("\"", addrStart);
                if (addrEnd == -1) return;
                String address = targetVout.substring(addrStart, addrEnd);

                long satValue = 0;
                int valIdx = targetVout.indexOf("\"value\":");
                if (valIdx!= -1) {
                    int valStart = valIdx + 8;
                    int valEnd = valStart;
                    while (valEnd < targetVout.length() && Character.isDigit(targetVout.charAt(valEnd))) valEnd++;
                    try { satValue = Long.parseLong(targetVout.substring(valStart, valEnd)); } catch (Exception ignored) {}
                }

                long feeSat = 0;
                int feeIdx = body.indexOf("\"fee\":");
                if (feeIdx!= -1) {
                    int s = feeIdx + 6;
                    int e = s;
                    while (e < body.length() && (Character.isDigit(body.charAt(e)) || body.charAt(e) == '-')) e++;
                    try { feeSat = Long.parseLong(body.substring(s, e).trim()); } catch (Exception ignored) {}
                }

                Coin coinValue = satValue > 0? Coin.valueOf(satValue) : null;
                String type = getAddressType(address, null);

                inputAddressCache.put(inputPos, address);
                if (coinValue!= null) inputValueCache.put(inputPos, coinValue);
                inputTypeCache.put(inputPos, type);

                long finalFeeSat = feeSat;
                mainHandler.post(() -> {
                    if (updateActualFrom) {
                        tvActualFrom.setText(address);
                        copyOnClick(tvActualFrom, address);
                    }
                    if (finalFeeSat > 0) {
                        tvFee.setText(Coin.valueOf(finalFeeSat).toPlainString() + getString(R.string.tx_details_btc_suffix));
                    }
                    renderInputsAndOutputs();
                    tryUpdateFee();
                    updateLiveQr();
                });

            } catch (Exception ignored) {}
        }).start();
    }

    // --- Recalculate fee if we now know all input values (offline or from cache) ---
    private void tryUpdateFee() {
        try {
            if (tx == null || tx.getInputs() == null || tx.getOutputs() == null) return;
            if (inputValueCache.size() < tx.getInputs().size()) {
                Coin totalInOffline = Coin.ZERO;
                boolean hasAll = true;
                for (int i = 0; i < tx.getInputs().size(); i++) {
                    TransactionInput in = tx.getInputs().get(i);
                    TransactionOutput conn = getConnectedOutput(in);
                    Coin v = null;
                    if (conn!= null) v = conn.getValue();
                    if (v == null) v = inputValueCache.get(i);
                    if (v == null) { hasAll = false; break; }
                    totalInOffline = totalInOffline.add(v);
                }
                if (!hasAll) return;
                Coin totalOut = Coin.ZERO;
                for (TransactionOutput out : tx.getOutputs()) if (out.getValue()!= null) totalOut = totalOut.add(out.getValue());
                if (!totalInOffline.isZero() && totalInOffline.isGreaterThan(totalOut)) {
                    Coin fee = totalInOffline.subtract(totalOut);
                    tvFee.setText(fee.toPlainString() + getString(R.string.tx_details_btc_suffix));
                }
                return;
            }

            Coin totalIn = Coin.ZERO;
            for (Coin c : inputValueCache.values()) if (c!= null) totalIn = totalIn.add(c);

            Coin totalOut = Coin.ZERO;
            for (TransactionOutput out : tx.getOutputs()) if (out.getValue()!= null) totalOut = totalOut.add(out.getValue());

            if (!totalIn.isZero() && totalIn.isGreaterThan(totalOut)) {
                Coin fee = totalIn.subtract(totalOut);
                tvFee.setText(fee.toPlainString() + getString(R.string.tx_details_btc_suffix));
                int weight = 0;
                try { weight = tx.getWeight(); } catch (Exception ignored) {}
                if (weight > 0) {
                    long satPerVbyte = fee.getValue() * 4 / weight;
                    String currentMeta = getTv(tvMeta);
                    if (!currentMeta.contains(getString(R.string.tx_details_fee_rate_suffix))) {
                        tvMeta.setText(currentMeta + " · " + satPerVbyte + getString(R.string.tx_details_fee_rate_suffix));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // --- Make TextView copyable on click ---
    private void copyOnClick(TextView tv, String text) {
        if (tv == null) return;
        final String t = text == null? "" : text;
        if (t.isEmpty() || t.equals(getString(R.string.tx_details_dash)) || t.equals(getString(R.string.tx_details_fetching))) {
            tv.setOnClickListener(null);
            return;
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

    // --- Dark mode detection ---
    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // --- QR setup ---
    private void setupQr() {
        if (ivQr!= null) ivQr.setOnClickListener(v -> showQrDialog());
    }

    // --- Build full text that will be encoded into QR (live fields included) ---
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

    private String getTv(TextView tv) { return tv!= null && tv.getText()!= null? tv.getText().toString() : ""; }

    // --- Update both small transparent QR and big dialog QR if visible ---
    private void updateLiveQr() {
        try {
            String text = buildLiveTxText();
            if (ivQr!= null) {
                Bitmap small = encodeQrTransparent(text, 768);
                ivQr.setImageBitmap(small);
            }
            if (qrDialog!= null && qrDialog.isShowing() && qrDialogImageView!= null) {
                Bitmap big = encodeQr(text, 1024);
                qrDialogImageView.setImageBitmap(big);
                currentQrBitmap = big;
            } else {
                currentQrBitmap = encodeQr(text, 1024);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void copyFullTx() { copy(buildLiveTxText()); }

    // --- Show fullscreen QR dialog with save/share/explore buttons ---
    private void showQrDialog() {
        boolean dark = isDark();
        int bgColor = dark? Color.BLACK : Color.WHITE;
        int dialogTheme = dark? android.R.style.Theme_Black_NoTitleBar_Fullscreen : android.R.style.Theme_Light_NoTitleBar_Fullscreen;
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
        qrDialogImageView.setPadding(48, 48, 48, 48);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        qrDialogImageView.setLayoutParams(imgLp);
        qrDialogImageView.setOnClickListener(v -> qrDialog.dismiss());
        root.addView(qrDialogImageView);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(16, 24, 16, 48);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);
        col.setClickable(true);
        col.setOnClickListener(onClick);
        col.setPadding(8, 8, 8, 8);
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        int iconSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36, getResources().getDisplayMetrics());
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        ivLp.gravity = Gravity.CENTER;
        iv.setLayoutParams(ivLp);
        col.addView(iv);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(dark? 0xFFBBBBBB : 0xFF666666);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 8, 0, 0);
        col.addView(tv);
        return col;
    }

    // --- Save QR bitmap to Pictures/WalletQR using MediaStore API (scoped storage compatible) ---
    private void saveQrBitmap() {
        try {
            Bitmap bmp = currentQrBitmap;
            if (bmp == null) bmp = encodeQr(buildLiveTxText(), 1024);
            String filename = "tx_" + (tx!= null? tx.getTxId().toString().substring(0, 8) : "qr") + "_" + System.currentTimeMillis() + ".png";
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
            String txid = tx!= null? tx.getTxId().toString() : getTv(tvTxid);
            String shareText = buildLiveTxText() + "\n\nhttps://mempool.space/tx/" + txid;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, shareText);
            startActivity(Intent.createChooser(i, getString(R.string.tx_details_share_tx)));
        } catch (Exception ignored) {}
    }

    private void exploreTx() {
        try {
            String txid = tx!= null? tx.getTxId().toString() : getTv(tvTxid);
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://mempool.space/tx/" + txid));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.tx_details_no_browser), Toast.LENGTH_SHORT).show();
        }
    }

    // --- QR encoding helpers ---
    // Standard black-on-white QR
    public static Bitmap encodeQr(String text, int size) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int w = bitMatrix.getWidth();
        int h = bitMatrix.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) bmp.setPixel(x, y, bitMatrix.get(x, y)? Color.BLACK : Color.WHITE);
        return bmp;
    }

    // Transparent background QR for overlay on card (small icon)
    public static Bitmap encodeQrTransparent(String text, int size) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        BitMatrix bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int w = bitMatrix.getWidth();
        int h = bitMatrix.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++)
            bmp.setPixel(x, y, bitMatrix.get(x, y)? Color.BLACK : Color.TRANSPARENT);
        return bmp;
    }

    /**
     * Calculate real calendar age from txTime to now.
     * Uses java.util.Calendar to respect real month lengths (28/29/30/31), leap years.
     * Algorithm: incrementally add years/months/days/hours/minutes from 'then' to 'now' using Calendar.add()
     * This guarantees correctness for edge cases like Jan 31 -> Feb 28/29, etc.
     */
    private String formatAge(Date txTime) {
        // --- 1. Null guard ---
        if (txTime == null) {
            return getString(R.string.tx_details_dash);
        }

        // --- 2. Prepare calendar instances ---
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTime(txTime);
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar cursor = (java.util.Calendar) then.clone();

        // --- 3. Future tx safety ---
        if (cursor.after(now)) {
            return "0 " + getString(R.string.qr_second) + " " + getString(R.string.qr_ago);
        }

        // --- 4. Calculate YEARS ---
        int years = 0;
        while (true) {
            java.util.Calendar next = (java.util.Calendar) cursor.clone();
            next.add(java.util.Calendar.YEAR, 1);
            if (next.after(now)) break;
            cursor.add(java.util.Calendar.YEAR, 1);
            years++;
        }

        // --- 5. Calculate MONTHS ---
        int months = 0;
        while (true) {
            java.util.Calendar next = (java.util.Calendar) cursor.clone();
            next.add(java.util.Calendar.MONTH, 1);
            if (next.after(now)) break;
            cursor.add(java.util.Calendar.MONTH, 1);
            months++;
        }

        // --- 6. Calculate DAYS ---
        int days = 0;
        while (true) {
            java.util.Calendar next = (java.util.Calendar) cursor.clone();
            next.add(java.util.Calendar.DAY_OF_MONTH, 1);
            if (next.after(now)) break;
            cursor.add(java.util.Calendar.DAY_OF_MONTH, 1);
            days++;
        }

        // --- 7. Calculate HOURS ---
        int hours = 0;
        while (true) {
            java.util.Calendar next = (java.util.Calendar) cursor.clone();
            next.add(java.util.Calendar.HOUR_OF_DAY, 1);
            if (next.after(now)) break;
            cursor.add(java.util.Calendar.HOUR_OF_DAY, 1);
            hours++;
        }

        // --- 8. Calculate MINUTES ---
        int minutes = 0;
        while (true) {
            java.util.Calendar next = (java.util.Calendar) cursor.clone();
            next.add(java.util.Calendar.MINUTE, 1);
            if (next.after(now)) break;
            cursor.add(java.util.Calendar.MINUTE, 1);
            minutes++;
        }

        // --- 9. Calculate SECONDS ---
        long remainingMillis = now.getTimeInMillis() - cursor.getTimeInMillis();
        int seconds = (int) (remainingMillis / 1000);
        if (seconds < 0) seconds = 0;
        if (seconds > 59) seconds = 59;

        // --- 10. Build localized string ---
        StringBuilder sb = new StringBuilder();
        if (years > 0) sb.append(years).append(" ").append(getString(years == 1? R.string.qr_year : R.string.qr_years)).append(" ");
        if (months > 0) sb.append(months).append(" ").append(getString(months == 1? R.string.qr_month : R.string.qr_months)).append(" ");
        if (days > 0) sb.append(days).append(" ").append(getString(days == 1? R.string.qr_day : R.string.qr_days)).append(" ");
        if (hours > 0 || sb.length() > 0) sb.append(hours).append(" ").append(getString(hours == 1? R.string.qr_hour : R.string.qr_hours)).append(" ");
        if (minutes > 0 || sb.length() > 0) sb.append(minutes).append(" ").append(getString(minutes == 1? R.string.qr_minute : R.string.qr_minutes)).append(" ");
        sb.append(seconds).append(" ").append(getString(seconds == 1? R.string.qr_second : R.string.qr_seconds)).append(" ");
        sb.append(getString(R.string.qr_ago));
        return sb.toString();
    }

    /**
     * Refresh live UI fields that change over time or with chain updates:
     * - Status (pending/building/confirmed) based on depth
     * - Confirmation height string
     * - Age (calls formatAge)
     * - QR (contains live age and status)
     */
    private void refreshLiveFields() {
        if (tx == null || tvStatus == null || tvHeight == null) return;
        TransactionConfidence confidence = tx.getConfidence();
        int depth = 0, height = 0;
        if (confidence!= null) {
            try { depth = confidence.getDepthInBlocks(); } catch (Exception ignored) {}
            try { height = confidence.getAppearedAtChainHeight(); } catch (Exception ignored) {}
        }
        String statusText;
        int statusColorRes;
        if (depth <= 0) {
            statusText = getString(R.string.tx_details_status_pending);
            statusColorRes = R.color.tx_status_pending;
        } else if (depth < 7) {
            statusText = getString(R.string.tx_details_status_building);
            statusColorRes = R.color.tx_status_building;
        } else {
            statusText = getString(R.string.tx_details_status_confirmed);
            statusColorRes = R.color.tx_status_ok;
        }
        tvStatus.setText(statusText);
        try { tvStatus.setTextColor(getResources().getColor(statusColorRes)); } catch (Exception ignored) {}
        String confStr = depth <= 0? getString(R.string.tx_details_unconfirmed) : getString(R.string.tx_details_confirmations_value, depth, height);
        tvHeight.setText(confStr);
        if (tvAge!= null) {
            Date updateTime = null;
            try { updateTime = tx.getUpdateTime(); } catch (Exception ignored) {}
            tvAge.setText(formatAge(updateTime));
        }
        updateLiveQr();
    }

    /**
     * Setup expandable cards: header click expands/collapses content, body click copies content.
     * Uses touch Y to distinguish header vs body click and foreground ripple hotspot.
     */
    private void setupExpandableCards() {
        final View contentSender = findViewById(R.id.content_sender);
        final View contentTxDetails = findViewById(R.id.content_tx_details);
        final View contentIo = findViewById(R.id.content_io);
        final View contentTxid = findViewById(R.id.content_txid);

        final androidx.cardview.widget.CardView cardSender = findViewById(R.id.card_sender);
        final androidx.cardview.widget.CardView cardDetails = findViewById(R.id.card_tx_details);
        final androidx.cardview.widget.CardView cardIo = findViewById(R.id.card_io);
        final androidx.cardview.widget.CardView cardTxid = findViewById(R.id.card_txid);

        final View headerSender = findViewById(R.id.header_sender);
        final View headerDetails = findViewById(R.id.header_tx_details);
        final View headerIo = findViewById(R.id.header_io);
        final View headerTxid = findViewById(R.id.header_txid);

        final int colorBg = getResources().getColor(R.color.tx_card_bg);
        final int colorExpanded = getResources().getColor(R.color.tx_card_expanded);

        final float[] lastTouchY = new float[4];

        View.OnTouchListener hotspotTouch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    if (v.getForeground()!= null) {
                        v.getForeground().setHotspot(event.getX(), event.getY());
                    }
                    if (v == cardSender) lastTouchY[0] = event.getY();
                    else if (v == cardDetails) lastTouchY[1] = event.getY();
                    else if (v == cardIo) lastTouchY[2] = event.getY();
                    else if (v == cardTxid) lastTouchY[3] = event.getY();
                }
                return false;
            }
        };

        cardSender.setOnTouchListener(hotspotTouch);
        cardDetails.setOnTouchListener(hotspotTouch);
        cardIo.setOnTouchListener(hotspotTouch);
        cardTxid.setOnTouchListener(hotspotTouch);

        View.OnClickListener cardClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                androidx.cardview.widget.CardView card = (androidx.cardview.widget.CardView) v;
                View content = null;
                View header = null;
                float touchY = 0;

                if (v == cardSender) { content = contentSender; header = headerSender; touchY = lastTouchY[0]; }
                else if (v == cardDetails) { content = contentTxDetails; header = headerDetails; touchY = lastTouchY[1]; }
                else if (v == cardIo) { content = contentIo; header = headerIo; touchY = lastTouchY[2]; }
                else if (v == cardTxid) { content = contentTxid; header = headerTxid; touchY = lastTouchY[3]; }

                if (content == null) return;

                boolean isHeaderClick = touchY <= header.getHeight();

                if (isHeaderClick) {
                    boolean shouldExpand = content.getVisibility()!= View.VISIBLE;

                    contentSender.setVisibility(View.GONE);
                    contentTxDetails.setVisibility(View.GONE);
                    contentIo.setVisibility(View.GONE);
                    contentTxid.setVisibility(View.GONE);

                    cardSender.setCardBackgroundColor(colorBg);
                    cardDetails.setCardBackgroundColor(colorBg);
                    cardIo.setCardBackgroundColor(colorBg);
                    cardTxid.setCardBackgroundColor(colorBg);

                    if (shouldExpand) {
                        content.setVisibility(View.VISIBLE);
                        card.setCardBackgroundColor(colorExpanded);
                    }
                } else {
                    if (v == cardSender) {
                        copy(getTv(tvActualFrom) + "\n" + getTv(tvActualTo));
                    } else if (v == cardDetails) {
                        String full = getString(R.string.tx_details_status) + ": " + getTv(tvStatus) + "\n" +
                                getString(R.string.tx_details_fee) + ": " + getTv(tvFee) + "\n" +
                                getString(R.string.tx_details_size_weight) + ": " + getTv(tvMeta) + "\n" +
                                getString(R.string.tx_details_confirmations) + ": " + getTv(tvHeight) + "\n" +
                                getString(R.string.tx_details_time) + ": " + getTv(tvTime) + "\n" +
                                getString(R.string.tx_details_age) + ": " + getTv(tvAge);
                        copy(full);
                    } else if (v == cardIo) {
                        copy(getTv(tvFrom) + "\n\n" + getTv(tvTo));
                    } else if (v == cardTxid) {
                        copy(getTv(tvTxid));
                    }
                }
            }
        };

        cardSender.setOnClickListener(cardClick);
        cardDetails.setOnClickListener(cardClick);
        cardIo.setOnClickListener(cardClick);
        cardTxid.setOnClickListener(cardClick);

        findViewById(R.id.ic_copy_sender).setOnClickListener(v -> copy(getTv(tvActualFrom) + "\n" + getTv(tvActualTo)));
        findViewById(R.id.ic_copy_details).setOnClickListener(v -> {
            String full = getString(R.string.tx_details_status) + ": " + getTv(tvStatus) + "\n" +
                    getString(R.string.tx_details_fee) + ": " + getTv(tvFee) + "\n" +
                    getString(R.string.tx_details_size_weight) + ": " + getTv(tvMeta) + "\n" +
                    getString(R.string.tx_details_confirmations) + ": " + getTv(tvHeight) + "\n" +
                    getString(R.string.tx_details_time) + ": " + getTv(tvTime) + "\n" +
                    getString(R.string.tx_details_age) + ": " + getTv(tvAge);
            copy(full);
        });
        findViewById(R.id.ic_copy_io).setOnClickListener(v -> copy(getTv(tvFrom) + "\n\n" + getTv(tvTo)));
        findViewById(R.id.ic_copy_txid).setOnClickListener(v -> copy(getTv(tvTxid)));
    }

    /**
     * Setup parallax quick-return header behavior.
     * Header slides out on scroll down, slides in on scroll up.
     */
    private void setupParallaxScroll() {
        final View scroll = findViewById(R.id.nested_scroll);
        final View cardHeader = findViewById(R.id.card_header);
        if (scroll == null || cardHeader == null) return;
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams layoutParams = new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(cardHeader.getLayoutParams().width, cardHeader.getLayoutParams().height);
        layoutParams.setBehavior(new QuickReturnBehavior());
        cardHeader.setLayoutParams(layoutParams);
        cardHeader.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top;
            int extra = (int) (0 * getResources().getDisplayMetrics().density);
            scroll.setPadding(scroll.getPaddingLeft(), height + extra, scroll.getPaddingRight(), scroll.getPaddingBottom());
        });
    }

    public static final class QuickReturnBehavior extends androidx.coordinatorlayout.widget.CoordinatorLayout.Behavior<View> {
        @Override public boolean onStartNestedScroll(androidx.coordinatorlayout.widget.CoordinatorLayout coordinatorLayout, View child, View directTargetChild, View target, int nestedScrollAxes, int type) {
            return (nestedScrollAxes & androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL)!= 0;
        }
        @Override public void onNestedScroll(androidx.coordinatorlayout.widget.CoordinatorLayout coordinatorLayout, View child, View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
            float newTrans = child.getTranslationY() - dyConsumed;
            float min = -child.getHeight();
            float max = 0;
            if (newTrans < min) newTrans = min;
            if (newTrans > max) newTrans = max;
            child.setTranslationY(newTrans);
        }
    }
}
