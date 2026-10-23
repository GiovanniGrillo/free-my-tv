package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The app's icon, with no window of its own: the settings, or on Fire TV the home screen (what Home on Fire opens for
 * the remote's Home button, since Amazon's home screen can't be replaced there). The gear on the home screen still
 * opens the settings directly.
 */
public class LaunchActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Class<?> target = HomeMode.FIRE_TV ? HomeActivity.class : SettingsActivity.class;
        startActivity(new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
        overridePendingTransition(0, 0);
    }
}
