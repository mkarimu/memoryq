package com.vstream.live;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * The first screen: before anything else, the user agrees to the terms, streaming rules, privacy policy and
 * disclaimers (asked again whenever Prefs.LEGAL_VERSION changes). Then it moves on to sign-in or straight to live.
 */
public class AgreementActivity extends AppCompatActivity {

    private CheckBox age;
    private CheckBox terms;
    private CheckBox safety;
    private Button agree;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.agreed(this)) {
            next();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        col.setPadding(pad, Ui.dp(this, 28), pad, pad);
        scroll.addView(col);

        col.addView(Ui.logo(this));
        Ui.add(col, Ui.note(this, "Go live from your phone in one tap. Before you start, please read and accept how Vstream works."), 6);

        LinearLayout card = Ui.card(this);
        Ui.add(card, Ui.text(this, "In short", 16, Ui.INK, true), 0);
        String[] points = {
            "You must be 18 or older to broadcast.",
            "You are responsible for everything you stream. No illegal, sexual, hateful or dangerous content, and no music or video you do not have the rights to.",
            "Streams to Memoryq are public. Memoryq picks a free channel for you; channels are shared and given back when unused.",
            "Your own server and other platforms (X, Facebook, Twitch, YouTube, TikTok) have their own rules. Vstream is not affiliated with them. Keep your stream keys private.",
            "Never stream while driving. Ask people before you film them - recording laws differ by place.",
            "Streaming uses a lot of mobile data. Vstream is provided as is, without warranties.",
        };
        for (String p : points) {
            Ui.add(card, Ui.note(this, "\u2022  " + p), 8);
        }
        Ui.add(col, card, 18);

        Ui.add(col, Ui.section(this, "Read the full text"), 0);
        String[][] docs = {
            {"Terms of Use", "terms"}, {"Streaming rules", "guidelines"}, {"Privacy policy", "privacy"},
            {"Copyright and music", "copyright"}, {"Other platforms and your own server", "platforms"}, {"Disclaimers and safety", "disclaimer"},
        };
        for (String[] d : docs) {
            Button b = Ui.flat(this, d[0] + "  \u203A");
            b.setOnClickListener(v -> Ui.openDoc(this, d[1]));
            Ui.add(col, b, 0);
        }

        age = check("I am 18 years old or older.");
        terms = check("I agree to the Terms of Use, Streaming rules and Privacy policy.");
        safety = check("I have read the disclaimers: I will not stream while driving, I will follow recording-consent laws, and I am responsible for my stream keys and other platforms' rules.");
        Ui.add(col, age, 18);
        Ui.add(col, terms, 8);
        Ui.add(col, safety, 8);

        agree = Ui.button(this, "I agree - continue", Ui.ACCENT);
        agree.setOnClickListener(v -> {
            Prefs.of(this).edit().putString("agreed", Prefs.LEGAL_VERSION).putLong("agreedAt", System.currentTimeMillis()).apply();
            next();
        });
        Ui.add(col, agree, 22);
        Button no = Ui.flat(this, "I do not agree");
        no.setTextColor(Ui.MUTED);
        no.setGravity(Gravity.CENTER);
        no.setOnClickListener(v -> finish());
        Ui.add(col, no, 4);
        refresh();
        setContentView(scroll);
        Ui.edgeSafe(this);
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(Ui.INK);
        c.setTextSize(15);
        c.setOnCheckedChangeListener((b, on) -> refresh());
        return c;
    }

    private void refresh() {
        boolean all = age.isChecked() && terms.isChecked() && safety.isChecked();
        agree.setEnabled(all);
        agree.setAlpha(all ? 1f : 0.45f);
    }

    private void next() {
        boolean ready = Prefs.signedIn(this) || Prefs.ownServer(this);
        startActivity(new Intent(this, ready ? LiveActivity.class : SignInActivity.class));
        finish();
    }
}