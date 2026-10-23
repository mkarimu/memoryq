package com.vstream.broadcaster;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Write a wall post: text, an optional photo from the phone, and who may see it (everyone or friends). */
public class WallComposerActivity extends AppCompatActivity {

    private static final int MAX_SIDE = 1280;

    private SharedPreferences prefs;
    private EditText text;
    private ImageView preview;
    private Button photoBtn;
    private Button postBtn;
    private RadioButton everyone;
    private byte[] jpeg;

    private final ActivityResultLauncher<Intent> pickPhoto = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent d = result.getData();
                if (result.getResultCode() == RESULT_OK && d != null && d.getData() != null) {
                    loadPhoto(d.getData());
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vstream", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(16), dp(4));
        TextView back = ProfileUi.text(this, "\u2039", 30, true);
        back.setPadding(dp(10), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(ProfileUi.text(this, "New post", 20, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        postBtn = new Button(this);
        postBtn.setText("Post");
        postBtn.setOnClickListener(v -> send());
        bar.addView(postBtn);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(16), dp(4), dp(16), dp(24));
        text = new EditText(this);
        text.setHint("What's on your mind?");
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setMinLines(4);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        inner.addView(text);

        photoBtn = new Button(this);
        photoBtn.setText("\ud83d\udcf7  Add a photo");
        photoBtn.setOnClickListener(v -> {
            if (jpeg != null) {
                jpeg = null;
                preview.setImageDrawable(null);
                preview.setVisibility(View.GONE);
                photoBtn.setText("\ud83d\udcf7  Add a photo");
                return;
            }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                pickPhoto.launch(Intent.createChooser(i, "Choose a photo"));
            } catch (RuntimeException e) {
                Toast.makeText(this, "No app found to choose photos", Toast.LENGTH_LONG).show();
            }
        });
        inner.addView(photoBtn);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(320));
        preview.setVisibility(View.GONE);
        inner.addView(preview);

        TextView who = ProfileUi.text(this, "Who can see it", 14, true);
        who.setPadding(0, dp(16), 0, 0);
        inner.addView(who);
        RadioGroup group = new RadioGroup(this);
        everyone = new RadioButton(this);
        everyone.setId(View.generateViewId());
        everyone.setText("Everyone - it can also appear in other people's news");
        RadioButton friends = new RadioButton(this);
        friends.setId(View.generateViewId());
        friends.setText("Friends only - people on your friends list");
        group.addView(everyone);
        group.addView(friends);
        group.check(everyone.getId());
        inner.addView(group);
        scroll.addView(inner);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private int dp(int v) {
        return ProfileUi.dp(this, v);
    }

    /** Turns the photo the right way up, shrinks it to at most 1280 pixels and saves it as a JPEG. */
    private void loadPhoto(final Uri uri) {
        photoBtn.setEnabled(false);
        new Thread(() -> {
            final PhotoUtil.Photo ph = PhotoUtil.load(this, uri, MAX_SIDE, 1500 * 1024);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                photoBtn.setEnabled(true);
                if (ph == null) {
                    Toast.makeText(this, "That photo could not be opened", Toast.LENGTH_LONG).show();
                    return;
                }
                jpeg = ph.jpeg;
                preview.setImageBitmap(ph.bitmap);
                preview.setVisibility(View.VISIBLE);
                photoBtn.setText("Remove the photo");
            });
        }).start();
    }

    private void send() {
        final String body = text.getText().toString().trim();
        if (body.isEmpty() && jpeg == null) {
            Toast.makeText(this, "Write something or add a photo", Toast.LENGTH_SHORT).show();
            return;
        }
        postBtn.setEnabled(false);
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        final String visibility = everyone.isChecked() ? "everyone" : "friends";
        final byte[] photo = jpeg;
        new Thread(() -> {
            String form = photo == null
                    ? Api.form("text", body, "visibility", visibility)
                    : Api.form("text", body, "visibility", visibility,
                    "image", "data:image/jpeg;base64," + Base64.encodeToString(photo, Base64.NO_WRAP));
            final Api.Result r = Api.post(host, "/api/wall/post", form, session);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (r.code == 200) {
                    Toast.makeText(this, "Posted", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    postBtn.setEnabled(true);
                    Toast.makeText(this, r.code == 0 ? "Cannot reach the server - try again" : r.error(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}