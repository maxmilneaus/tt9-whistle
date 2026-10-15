package io.github.sspanak.tt9.dictation;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;

/** DEBUG BUILDS ONLY. Two plain text fields, to check where dictated text lands. */
public class TestFieldsActivity extends Activity {
	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(32, 120, 32, 32);
		for (String name : new String[] { "field_a", "field_b" }) {
			EditText field = new EditText(this);
			field.setHint(name);
			field.setTag(name);
			root.addView(field);
		}
		setContentView(root);
	}
}
