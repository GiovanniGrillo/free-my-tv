package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Fire TV: an HDMI input, full screen, in the app's own TvView. Amazon keeps its input player to its own apps
 * (TUNE_INPUT), and the standard request for an input lands on Live TV's last channel (an app's, like RaiPlay's)
 * instead. Back goes back home.
 */
public class HdmiActivity extends Activity {
    static final String EXTRA_INPUT = "input", EXTRA_LABEL = "label";
    private TvView tv;
    private TextView noSignal;
    private String tuned;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        tv = new TvView(this);
        // Over the black screen while the input sends no picture (the device on it is off or asleep).
        noSignal = new TextView(this);
        noSignal.setGravity(Gravity.CENTER);
        noSignal.setTextColor(0xD9FFFFFF);
        noSignal.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * getResources().getDisplayMetrics().widthPixels / 1920f);
        noSignal.setTypeface(Ui.medium);
        noSignal.setLineSpacing(0, 1.4f);
        noSignal.setVisibility(TextView.GONE);
        tv.setCallback(new TvView.TvInputCallback() {
            @Override public void onConnectionFailed(String inputId) {
                AppLog.w("HDMI: no connection to " + inputId);
            }

            @Override public void onVideoUnavailable(String inputId, int reason) {
                AppLog.w("HDMI: no picture on " + inputId + " (" + reason + ")");
                noSignal.setVisibility(TextView.VISIBLE);
            }

            @Override public void onVideoAvailable(String inputId) {
                noSignal.setVisibility(TextView.GONE);
            }
        });
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(tv);
        root.addView(noSignal);
        setContentView(root);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        String input = getIntent().getStringExtra(EXTRA_INPUT);
        if (input == null) {
            finish();
            return;
        }
        if (!input.equals(tuned)) {
            tuned = input;
            String label = getIntent().getStringExtra(EXTRA_LABEL);
            noSignal.setText(Ui.trf("No signal on {input}", "input", label == null ? "HDMI" : label) + "\n"
                    + Ui.tr("Back returns to the home screen."));
            tv.tune(input, TvContract.buildChannelUriForPassthroughInput(input));
            AppLog.w("HDMI: showing " + input);
        }
    }

    @Override protected void onStop() {
        // Off screen, the input is let go (the next opening tunes again).
        tv.reset();
        tuned = null;
        super.onStop();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // The remote's keys stay with this screen (Back leaves), not with the device on HDMI.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}
