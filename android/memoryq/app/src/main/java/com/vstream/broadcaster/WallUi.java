package com.vstream.broadcaster;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wall post cards (author, text, picture, likes, comments, delete or report) and loading someone's wall into a
 * list. Comments open inside the card as a tree: each comment can be answered, and answers can be answered too.
 */
final class WallUi {

    private WallUi() {
    }

    static String ago(long ms) {
        if (ms <= 0) {
            return "";
        }
        long minutes = Math.max(0, System.currentTimeMillis() - ms) / 60000;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " h ago";
        }
        return (hours / 24) + " d ago";
    }

    /** One post. onGone runs after the post was deleted (to remove the card). */
    static View card(final Activity a, final SharedPreferences prefs, final JSONObject p, final boolean showViewWall) {
        final String host = prefs.getString("host", "");
        boolean dark = ProfileUi.dark(a);
        final LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = ProfileUi.dp(a, 12);
        c.setPadding(pad, ProfileUi.dp(a, 10), pad, ProfileUi.dp(a, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ProfileUi.dp(a, 10));
        bg.setColor(dark ? 0xFF2B2B2B : 0xFFF1F3F4);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, ProfileUi.dp(a, 8));
        c.setLayoutParams(lp);

        // Author line.
        final String author = p.optString("author");
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView av = ProfileUi.roundImage(a, 36, true);
        ImageLoader.load(av, host, author, true);
        head.addView(av);
        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(ProfileUi.dp(a, 10), 0, 0, 0);
        String stage = p.optString("stageName");
        String name = p.optString("name");
        names.addView(ProfileUi.text(a, !stage.isEmpty() ? stage : (!name.isEmpty() ? name : author), 14, true));
        String country = p.optString("country");
        String sub = ago(p.optLong("created")) + (country.isEmpty() ? "" : "  \u00b7  " + country)
                + (p.optBoolean("friendsOnly") ? "  \u00b7  \ud83d\udd12 friends" : "")
                + (p.optBoolean("test") ? "  \u00b7  test" : "");
        TextView s = ProfileUi.text(a, sub, 12, false);
        s.setAlpha(0.7f);
        names.addView(s);
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.setOnClickListener(v -> ProfileUi.openProfile(a, author));
        c.addView(head);

        String text = p.optString("text");
        if (!text.isEmpty()) {
            TextView t = ProfileUi.text(a, text, 15, false);
            t.setPadding(0, ProfileUi.dp(a, 8), 0, 0);
            t.setTextIsSelectable(true);
            c.addView(t);
        }
        String imageUrl = p.optString("imageUrl");
        if (!imageUrl.isEmpty()) {
            ImageView img = new ImageView(a);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setMaxHeight(ProfileUi.dp(a, 380));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.setMargins(0, ProfileUi.dp(a, 8), 0, 0);
            img.setLayoutParams(ilp);
            img.setMinimumHeight(ProfileUi.dp(a, 120));
            ImageLoader.loadPath(img, host, imageUrl, "w|" + p.optLong("id"));
            c.addView(img);
        }

        // Like, view wall, more.
        LinearLayout actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, ProfileUi.dp(a, 4), 0, 0);
        final long id = p.optLong("id");
        final boolean[] liked = {p.optBoolean("liked")};
        final int[] likes = {p.optInt("likes")};
        final TextView like = action(a, likeLabel(liked[0], likes[0]));
        like.setOnClickListener(v -> {
            liked[0] = !liked[0];
            likes[0] += liked[0] ? 1 : -1;
            like.setText(likeLabel(liked[0], likes[0]));
            final boolean want = liked[0];
            final String session = prefs.getString("session", "");
            new Thread(() -> Api.post(host, "/api/wall/like", Api.form("id", String.valueOf(id), "like", String.valueOf(want)),
                    session)).start();
        });
        actions.addView(like);
        final int[] count = {p.optInt("comments")};
        final TextView commentBtn = action(a, commentLabel(count[0]));
        commentBtn.setTag("comments-toggle");
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        final java.util.Set<Long> open = new java.util.HashSet<>();
        commentBtn.setOnClickListener(v -> {
            if (box.getVisibility() == View.VISIBLE) {
                box.setVisibility(View.GONE);
                return;
            }
            box.setVisibility(View.VISIBLE);
            loadComments(a, prefs, id, box, open, n -> {
                count[0] = n;
                commentBtn.setText(commentLabel(n));
            });
        });
        actions.addView(commentBtn);
        View space = new View(a);
        actions.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView more = action(a, "\u22ef");
        final boolean mine = p.optBoolean("mine");
        more.setOnClickListener(v -> {
            String[] items = mine ? new String[]{"Delete post"} : new String[]{"Report post"};
            new AlertDialog.Builder(a)
                    .setItems(items, (d, w) -> {
                        if (mine) {
                            confirmDelete(a, prefs, id, c);
                        } else {
                            report(a, prefs, id);
                        }
                    })
                    .show();
        });
        actions.addView(more);
        c.addView(actions);
        c.addView(box);
        return c;
    }

    private static String commentLabel(int n) {
        return "\uD83D\uDCAC " + (n == 0 ? "Comment" : n + (n == 1 ? " comment" : " comments"));
    }

    interface Count {
        void set(int n);
    }

    // ---- Comments ----

    /** Loads a post's comments into box (replacing what is there) and keeps the count on the card up to date. */
    static void loadComments(final Activity a, final SharedPreferences prefs, final long post, final LinearLayout box,
                             final java.util.Set<Long> open, final Count count) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        if (box.getChildCount() == 0) {
            TextView loading = ProfileUi.text(a, "Loading comments...", 13, false);
            loading.setAlpha(0.7f);
            box.addView(loading);
        }
        new Thread(() -> {
            final Api.Result r = Api.get(host, "/api/wall/comments?post=" + post, session);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                box.removeAllViews();
                if (r.code != 200) {
                    box.addView(ProfileUi.text(a, r.code == 0 ? "Cannot reach the server" : r.error(), 13, false));
                    return;
                }
                JSONObject o = r.json();
                count.set(o.optInt("count"));
                JSONArray list = o.optJSONArray("comments");
                java.util.Map<Long, java.util.List<JSONObject>> kids = new java.util.LinkedHashMap<>();
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject cm = list.optJSONObject(i);
                    if (cm != null) {
                        long parent = cm.optLong("parent");
                        java.util.List<JSONObject> l = kids.get(parent);
                        if (l == null) {
                            l = new java.util.ArrayList<>();
                            kids.put(parent, l);
                        }
                        l.add(cm);
                    }
                }
                final boolean canComment = o.optBoolean("canComment");
                Runnable reload = () -> loadComments(a, prefs, post, box, open, count);
                java.util.List<JSONObject> top = kids.get(0L);
                if (top == null || top.isEmpty()) {
                    TextView none = ProfileUi.text(a, "No comments yet.", 13, false);
                    none.setAlpha(0.7f);
                    none.setPadding(0, ProfileUi.dp(a, 4), 0, ProfileUi.dp(a, 4));
                    box.addView(none);
                } else {
                    for (JSONObject cm : top) {
                        box.addView(commentView(a, prefs, post, cm, kids, 0, canComment, open, reload));
                    }
                }
                if (canComment) {
                    box.addView(inputRow(a, prefs, post, 0, "Write a comment...", reload));
                } else {
                    TextView closed = ProfileUi.text(a, "You can't comment on this post.", 12, false);
                    closed.setAlpha(0.6f);
                    box.addView(closed);
                }
            });
        }).start();
    }

    /** One comment, its Reply button, and its replies (hidden behind "View N replies" until opened). */
    private static View commentView(final Activity a, final SharedPreferences prefs, final long post, final JSONObject cm,
                                    final java.util.Map<Long, java.util.List<JSONObject>> kids, final int depth,
                                    final boolean canComment, final java.util.Set<Long> open, final Runnable reload) {
        final long id = cm.optLong("id");
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(depth == 0 ? 0 : ProfileUi.dp(a, Math.min(depth, 4) > 0 ? 18 : 0), ProfileUi.dp(a, 4), 0, 0);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String author = cm.optString("author");
        boolean deleted = cm.optBoolean("deleted");
        ImageView av = ProfileUi.roundImage(a, 28, true);
        if (!deleted) {
            ImageLoader.load(av, prefs.getString("host", ""), author, true);
            av.setOnClickListener(v -> ProfileUi.openProfile(a, author));
        }
        row.addView(av);
        LinearLayout bubble = new LinearLayout(a);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(ProfileUi.dp(a, 10), ProfileUi.dp(a, 6), ProfileUi.dp(a, 10), ProfileUi.dp(a, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(ProfileUi.dp(a, 12));
        bg.setColor(ProfileUi.dark(a) ? 0xFF3A3A3A : 0xFFE4E6EB);
        bubble.setBackground(bg);
        if (deleted) {
            TextView t = ProfileUi.text(a, "Comment deleted", 13, false);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.ITALIC);
            t.setAlpha(0.6f);
            bubble.addView(t);
        } else {
            String stage = cm.optString("stageName");
            TextView who = ProfileUi.text(a, stage.isEmpty() ? author : stage, 13, true);
            who.setOnClickListener(v -> ProfileUi.openProfile(a, author));
            bubble.addView(who);
            TextView t = ProfileUi.text(a, cm.optString("text"), 14, false);
            t.setTextIsSelectable(false);
            bubble.addView(t);
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(ProfileUi.dp(a, 6), 0, 0, 0);
        row.addView(bubble, blp);
        if (cm.optBoolean("canDelete")) {
            bubble.setOnLongClickListener(v -> {
                new AlertDialog.Builder(a)
                        .setItems(new String[]{"Delete comment"}, (d, w) -> deleteComment(a, prefs, id, reload))
                        .show();
                return true;
            });
        }
        wrap.addView(row);

        // Time, Reply, and the replies.
        LinearLayout meta = new LinearLayout(a);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setPadding(ProfileUi.dp(a, 40), 0, 0, 0);
        TextView when = ProfileUi.text(a, ago(cm.optLong("created")), 11, false);
        when.setAlpha(0.6f);
        meta.addView(when);
        final LinearLayout replyBox = new LinearLayout(a);
        replyBox.setOrientation(LinearLayout.VERTICAL);
        if (canComment && !deleted) {
            TextView reply = small(a, "Reply");
            reply.setOnClickListener(v -> {
                if (replyBox.getChildCount() == 0) {
                    open.add(id);
                    replyBox.addView(inputRow(a, prefs, post, id, "Reply to " + (cm.optString("stageName").isEmpty()
                            ? author : cm.optString("stageName")) + "...", reload));
                } else {
                    replyBox.removeAllViews();
                }
            });
            meta.addView(reply);
        }
        wrap.addView(meta);

        java.util.List<JSONObject> children = kids.get(id);
        int n = children == null ? 0 : children.size();
        final LinearLayout childBox = new LinearLayout(a);
        childBox.setOrientation(LinearLayout.VERTICAL);
        if (n > 0) {
            for (JSONObject ch : children) {
                childBox.addView(commentView(a, prefs, post, ch, kids, depth + 1, canComment, open, reload));
            }
            final boolean shown = open.contains(id);
            childBox.setVisibility(shown ? View.VISIBLE : View.GONE);
            final int replies = cm.optInt("replies", n);
            final TextView toggle = small(a, shown ? "Hide replies" : "\u21B3 View " + replies + (replies == 1 ? " reply" : " replies"));
            toggle.setPadding(ProfileUi.dp(a, 40), ProfileUi.dp(a, 2), 0, ProfileUi.dp(a, 2));
            toggle.setOnClickListener(v -> {
                boolean nowShown = childBox.getVisibility() != View.VISIBLE;
                childBox.setVisibility(nowShown ? View.VISIBLE : View.GONE);
                if (nowShown) {
                    open.add(id);
                } else {
                    open.remove(id);
                }
                toggle.setText(nowShown ? "Hide replies" : "\u21B3 View " + replies + (replies == 1 ? " reply" : " replies"));
            });
            wrap.addView(toggle);
        }
        wrap.addView(replyBox);
        wrap.addView(childBox);
        return wrap;
    }

    private static TextView small(Activity a, String label) {
        TextView t = ProfileUi.text(a, label, 12, true);
        t.setPadding(ProfileUi.dp(a, 12), ProfileUi.dp(a, 4), ProfileUi.dp(a, 8), ProfileUi.dp(a, 4));
        t.setAlpha(0.8f);
        return t;
    }

    /** A text box with a Send button; parent 0 answers the post, otherwise that comment. */
    private static View inputRow(final Activity a, final SharedPreferences prefs, final long post, final long parent,
                                 String hint, final Runnable reload) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(parent == 0 ? 0 : ProfileUi.dp(a, 34), ProfileUi.dp(a, 4), 0, ProfileUi.dp(a, 4));
        final android.widget.EditText input = new android.widget.EditText(a);
        input.setHint(hint);
        input.setTextSize(14);
        input.setMaxLines(4);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView send = ProfileUi.text(a, "Send", 14, true);
        send.setTextColor(0xFF1E88E5);
        send.setPadding(ProfileUi.dp(a, 10), ProfileUi.dp(a, 8), ProfileUi.dp(a, 6), ProfileUi.dp(a, 8));
        send.setOnClickListener(v -> {
            final String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            send.setEnabled(false);
            final String host = prefs.getString("host", "");
            final String session = prefs.getString("session", "");
            new Thread(() -> {
                final Api.Result r = Api.post(host, "/api/wall/comment", Api.form("post", String.valueOf(post),
                        "parent", String.valueOf(parent), "text", text), session);
                a.runOnUiThread(() -> {
                    send.setEnabled(true);
                    if (r.code == 200) {
                        input.setText("");
                        reload.run();
                    } else {
                        Toast.makeText(a, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        });
        row.addView(send);
        return row;
    }

    private static void deleteComment(final Activity a, final SharedPreferences prefs, final long id, final Runnable reload) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.post(host, "/api/wall/comment/delete", Api.form("id", String.valueOf(id)), session);
            a.runOnUiThread(() -> {
                if (r.code == 200) {
                    reload.run();
                } else {
                    Toast.makeText(a, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static String likeLabel(boolean liked, int n) {
        return (liked ? "\u2665 " : "\u2661 ") + (n > 0 ? String.valueOf(n) : "Like");
    }

    private static TextView action(Activity a, String label) {
        TextView t = ProfileUi.text(a, label, 14, true);
        t.setPadding(ProfileUi.dp(a, 4), ProfileUi.dp(a, 8), ProfileUi.dp(a, 14), ProfileUi.dp(a, 8));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            t.setBackgroundResource(tv.resourceId);
        }
        return t;
    }

    private static void confirmDelete(final Activity a, final SharedPreferences prefs, final long id, final View card) {
        new AlertDialog.Builder(a)
                .setTitle("Delete this post?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    final String host = prefs.getString("host", "");
                    final String session = prefs.getString("session", "");
                    new Thread(() -> {
                        Api.Result r = Api.post(host, "/api/wall/delete", Api.form("id", String.valueOf(id)), session);
                        a.runOnUiThread(() -> {
                            if (r.code == 200) {
                                ViewGroup parent = (ViewGroup) card.getParent();
                                if (parent != null) {
                                    parent.removeView(card);
                                }
                                Toast.makeText(a, "Post deleted", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(a, r.code == 0 ? "Cannot reach the server" : r.error(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }).start();
                })
                .show();
    }

    private static void report(final Activity a, final SharedPreferences prefs, final long id) {
        final String[] reasons = {"Spam", "Harassment or bullying", "Hate or violence", "Nudity or sexual content",
            "Something else"};
        new AlertDialog.Builder(a)
                .setTitle("Why are you reporting this post?")
                .setItems(reasons, (d, w) -> {
                    final String host = prefs.getString("host", "");
                    final String session = prefs.getString("session", "");
                    new Thread(() -> {
                        Api.Result r = Api.post(host, "/api/wall/report",
                                Api.form("id", String.valueOf(id), "reason", reasons[w]), session);
                        a.runOnUiThread(() -> Toast.makeText(a, r.code == 200 ? "Thanks - the admin will look at it"
                                : (r.code == 0 ? "Cannot reach the server" : r.error()), Toast.LENGTH_LONG).show());
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Loads someone's wall (20 posts at a time, with a "More posts" button) into the list. */
    static void loadWall(final Activity a, final SharedPreferences prefs, final String user, final LinearLayout into,
                         final long before) {
        final String host = prefs.getString("host", "");
        final String session = prefs.getString("session", "");
        new Thread(() -> {
            final Api.Result r = Api.get(host, "/api/wall/user?" + Api.form("u", user, "before", String.valueOf(before)), session);
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                if (r.code != 200) {
                    into.addView(ProfileUi.text(a, r.code == 0 ? "Cannot reach the server"
                            : (r.code == 404 ? "Walls need the latest server update." : r.error()), 14, false));
                    return;
                }
                JSONArray posts = r.json().optJSONArray("posts");
                int n = posts == null ? 0 : posts.length();
                if (n == 0 && before == 0) {
                    TextView none = ProfileUi.text(a, "No posts yet.", 14, false);
                    none.setAlpha(0.7f);
                    into.addView(none);
                }
                for (int i = 0; i < n; i++) {
                    JSONObject p = posts.optJSONObject(i);
                    if (p != null) {
                        into.addView(card(a, prefs, p, false));
                    }
                }
                if (r.json().optBoolean("more")) {
                    final long next = r.json().optLong("next");
                    final Button more = new Button(a);
                    more.setText("More posts");
                    more.setOnClickListener(v -> {
                        into.removeView(more);
                        loadWall(a, prefs, user, into, next);
                    });
                    into.addView(more);
                }
            });
        }).start();
    }
}