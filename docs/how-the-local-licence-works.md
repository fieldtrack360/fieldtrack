# How the local licence works

> **Removed from the SDK.** The offline gate this page describes no longer exists in the
> code: the licence token is not verified locally, and `ready()` never fails with
> `LICENSE_MISSING` / `LICENSE_INVALID` / `LICENSE_BUNDLE_MISMATCH`. The token now feeds
> only the online revocation check, which remains the sole enforcement (revoked/expired
> stops tracking). This page is kept as historical reference for the token format.

Plain-English explanation of the licence check that runs **inside the app, with no
internet**. No prior knowledge assumed.

There is a second, separate check that *does* use the internet — it asks our server
whether a licence has been cancelled since it was sold. That one is described in
[`android-kotlin-integration.md`](android-kotlin-integration.md). This page is only about
the offline half, which is the half that decides whether the SDK runs at all.

---

## 1. The one-sentence version

The customer is given a long piece of text. That text says which app it is for, and it
carries a mark that only we can make but anybody can check. The app checks the mark, checks
the app name matches, and starts.

---

## 2. Why this is harder than it looks

The obvious way to build licensing is: the app sends the key to our server, the server says
yes or no. That is simple, and it is wrong for this SDK, for three reasons.

**Field workers go where there is no signal.** A tracking SDK that will not start
underground, in a warehouse, or on a rural route is not a tracking SDK. If starting
required our server, our server going down would stop every customer's app at once.

**We would learn nothing useful and cost everyone battery.** Asking on every launch means
a network round trip in front of every single start.

**It would not actually be more secure.** Anyone can point their phone at a fake server
that says "yes" to everything.

So the check has to work with no network, on a device we do not control, against an answer
we cannot look up. That sounds impossible. It is not — and section 4 is the trick that
makes it work.

---

## 3. What a licence key actually is

It looks like this (shortened here):

```
TRACKIT-eyJpc3N1ZWQiOiIyMDI2LTA4LTIwIiwia2lkIjox...fQ.bKjxaZmcCVzE7rSkf_sVesGCFbvF...CQ
└──┬──┘ └──────────────────┬─────────────────────┘  └─────────────┬──────────────────┘
 prefix              the details                              the mark
```

Three parts, separated by a full stop:

**The prefix — `TRACKIT-`.** Just a label, so a human pasting the wrong string into the
wrong box finds out immediately.

**The details.** This is ordinary text that has been scrambled into letters and numbers so
it survives being emailed and pasted around. Unscrambled, it reads:

```json
{
  "primary":  "com.acme.app",
  "also":     [],
  "kid":      1,
  "v":        1,
  "licensee": "Acme Logistics",
  "issued":   "2026-08-20"
}
```

| Field | Plain meaning |
|---|---|
| `primary` | **Which app this licence is for.** Every Android app has a unique id like `com.acme.app` |
| `also` | Other app ids covered by the same licence — for a customer who ships a `.dev` build too |
| `kid` | **Which of our seals was used.** See section 6 |
| `v` | The format version. Lets us change the format later without old apps guessing |
| `licensee` | The customer's name. For support, not for checking |
| `issued` | The date it was made. For support, not for checking |

**Important:** this part is *not* secret. Anyone can unscramble and read it. It is not
meant to hide anything. Its job is to *state* things.

**The mark.** The part that makes the details trustworthy. Section 4.

---

## 4. The trick: a mark anyone can check but only we can make

This is the one idea worth understanding. Everything else follows from it.

Imagine a wax seal on a letter. Two useful properties:

- Only the person with the seal-stamp can create it.
- **Anyone holding a picture of the seal can tell a real one from a fake one.**

Those are different abilities. Being able to *recognise* a seal does not let you *make*
one. That gap is what makes this work.

The maths behind licence keys does exactly this, with two matching pieces:

| | Who has it | What it can do |
|---|---|---|
| **Private key** | Only us, on a server nobody else touches | **Make** the mark |
| **Public key** | Built into every copy of the SDK | **Check** the mark. Cannot make one |

So we build the public key into the app and we are not worried about anyone finding it.
Finding it gets you nothing. It is the picture of the seal, not the stamp.

The mark also covers **the exact details**. Change one character of the app name inside a
licence and the mark stops matching — like a seal across the flap of an envelope. You
cannot open it, edit it, and re-seal it, because you do not have the stamp.

> **This is the whole answer to "how can the app check a licence with no internet?"**
> It does not need to ask us anything. We already answered, in advance, when we made the
> mark. The app is just reading an answer that was sealed at the time the licence was sold.

---

## 5. What the app does, step by step

Every time the host app starts the SDK — `tracker.ready()` — this runs first, before
anything else. It takes about a millisecond and touches no network.

```
                    ready()
                       │
        ┌──────────────▼──────────────┐
        │ Is this a development build?│──── yes ──▶ SKIP EVERYTHING. Start. (see §7)
        └──────────────┬──────────────┘
                       │ no
        ┌──────────────▼──────────────┐
        │ 1. Do we have a licence?    │──── no  ──▶ LICENSE_MISSING
        └──────────────┬──────────────┘
        ┌──────────────▼──────────────┐
        │ 2. Is it shaped like one?   │──── no  ──▶ LICENSE_INVALID
        └──────────────┬──────────────┘
        ┌──────────────▼──────────────┐
        │ 3. Is it for THIS app?      │──── no  ──▶ LICENSE_BUNDLE_MISMATCH
        └──────────────┬──────────────┘
        ┌──────────────▼──────────────┐
        │ 4. Do we know that seal?    │──── no  ──▶ LICENSE_INVALID
        └──────────────┬──────────────┘
        ┌──────────────▼──────────────┐
        │ 5. Is the mark genuine?     │──── no  ──▶ LICENSE_INVALID
        └──────────────┬──────────────┘
                       │ yes
                    ▼ Start.
```

### Step 1 — Do we have a licence?

The app looks in two places, in order:

1. What the developer passed in code: `.license("TRACKIT-...")`
2. An entry in the app's manifest named exactly `TrackItLicense`

The first one wins if both exist. If neither, that is `LICENSE_MISSING` — nothing is wrong
with the licence, there just isn't one.

> **The key is never saved to the phone's storage by the SDK.** It is re-read from the
> app's own settings on every start. This sounds like a small detail; it is not. If we
> cached it, a customer who updated their licence would keep running on the old one, and
> "I updated my licence and nothing happened" is a support ticket nobody can diagnose.

### Step 2 — Is it shaped like one?

Quick sanity checks before any real work: does it start with `TRACKIT-`, are there exactly
two parts separated by a full stop, do both parts unscramble, are the details readable, is
the version one we understand?

Most real-world failures land here, and they are almost always the same thing: **someone
pasted only part of the key.** They are long, and text boxes truncate.

### Step 3 — Is it for THIS app?

The app asks the operating system its own id, and compares it to `primary` (and `also`) in
the licence.

**This is what stops a licence being passed around.** Buy one, publish it on a forum, and
it is useless to everybody else — their app has a different id, and they cannot change the
id inside the licence without breaking the mark.

The app id is sealed in at the moment we issue the key and cannot be edited afterwards. If
a customer needs a different one, we issue a new key.

### Step 4 — Do we know that seal?

The licence says which seal we used (`kid`, short for *key id*). The SDK carries a small
list of seals it recognises. It looks up that number.

Why a list rather than one? Because seals sometimes have to be replaced, and existing
licences still carry the old number. Holding a list means we add a new seal and both old
and new licences keep working. Holding one value would mean every existing customer breaks
on the day we change it. Section 6 has more.

If the number is not in the list: `LICENSE_INVALID`, "Unknown license key id".

### Step 5 — Is the mark genuine?

The real check. The app takes the details, takes the mark, takes the seal picture from step
4, and asks: *could this mark have been made for these exact details by whoever owns this
seal?*

Yes → start. No → `LICENSE_INVALID`.

A "no" here means one of two things: someone edited the licence, or someone tried to
manufacture one. Both are the same answer.

---

## 6. Why the SDK holds a *list* of seals

A short section on a decision that looks like over-engineering and is not.

Every licence records which seal made it. The SDK holds seal number → seal picture.

Today there is one entry. The temptation is to store a single value and be done.

Here is what happens if we do. One day a seal has to be replaced — routine maintenance, or
something went wrong. We ship an SDK with the new seal. **Every licence already in the
field was made with the old one**, so every existing customer's app stops recognising its
own licence, all at once, on the day they update.

With a list, we add the new seal, keep the old one, and nothing breaks. Old licences verify
against the old entry, new ones against the new entry, and the old entry is removed years
later when nothing uses it.

The cost of the list today is a few lines. The cost of not having it arrives at the worst
possible moment.

---

## 7. Development builds skip all of this

If the app is built in "debug" mode — what a developer runs from Android Studio — the
entire check is skipped and the SDK just starts.

**Why:** a developer trying the SDK for ten minutes should not need to talk to sales
first. It removes the single biggest reason people give up on an SDK before evaluating it.

**Why it is not a hole:** debug mode is a flag Android sets at build time. An app on the
Play Store cannot have it. To exploit this you would have to rebuild the host app yourself
in debug mode — and if you can rebuild the app, you could have removed the licence check
entirely. It defends nothing that was defensible.

**The trap this creates**, and it catches people:

> Licensing is invisible during development. Everything works, no key needed. Then the
> first release build refuses to start, in front of whoever is watching. **Always test a
> release build before you need one.**

---

## 8. What can go wrong, in plain terms

| What the app reports | What it means | Usual cause |
|---|---|---|
| `LICENSE_MISSING` | No key was supplied | Key added to the debug config but not the release one |
| `LICENSE_INVALID` | The key is damaged, edited, or fake | **Almost always a truncated paste.** Copy the whole thing, including `TRACKIT-` |
| `LICENSE_BUNDLE_MISMATCH` | The key is for a different app | Build flavours and `.dev` suffixes change the app id. Ask for a key covering both |

Only three outcomes, and all three are refusals to start. Nothing here stops an app that
is already running — that is the online check's job, and it is deliberately much gentler.

---

## 9. Honest limits

What this does **not** do, stated plainly, because a security measure whose limits are
oversold is worse than one whose limits are known.

**It cannot stop someone who edits the SDK itself.** Anyone determined enough can take the
SDK apart and remove the check. Nothing running on a device the attacker owns can prevent
that. What licensing does is make honest use easy and casual copying pointless — it is a
lock on a door, not a vault.

**It cannot revoke.** Once a licence is issued, this check will accept it forever. It
cannot know about a refund, a chargeback, or a cancellation, because it cannot ask anybody
— that is the price of working with no internet. The online check exists for exactly that
gap.

**It cannot expire.** Same reason. A trial's end date is enforced by the online check, not
this one.

**The key is readable inside the app.** Anyone who unpacks an installed app can find the
licence key in it. That is expected and fine: the key is bound to that app's id, so a copy
of it is worth nothing anywhere else.

---

## 10. Common questions

**If the public key is inside the app, can someone forge a licence with it?**
No. Checking and making are different abilities, and only the checking half is in the app.
See section 4.

**Can someone change the app id inside a licence?**
They can edit the text, but the mark then stops matching and the check fails. They cannot
produce a new mark for the edited text without our private key.

**What if I use one licence in two apps?**
It works only in the app ids written inside it. Ask for a key covering both — the `also`
field exists for exactly this.

**Does this slow down startup?**
No. It is arithmetic on a few hundred bytes, no file reads, no network. Well under a
millisecond.

**Does it work with no internet?**
Yes, permanently and by design. That is the entire point of this half of the system.

**What if your licence server is down?**
Irrelevant to this check — it never contacts a server. The online check does, and it is
built to shrug that off too: if it cannot reach us, tracking carries on.

**Where does the key go in my project?**
`local.properties`, which is excluded from version control. See
[`INTEGRATION-GUIDE.md` §2](INTEGRATION-GUIDE.md#2-license-token).

---

## 11. In one paragraph

We sell a piece of text that states which app it is for and carries a mark only we can
make. Every copy of the SDK carries the means to recognise that mark, but not to make one.
When an app starts, the SDK reads the text, confirms the app id inside matches the app it
is actually running in, and confirms the mark is genuine. No network, no server, no delay.
The licence cannot be edited without breaking the mark, and cannot be reused in another app
because the app id is sealed inside it. It cannot be taken back either — which is why a
separate, gentler check runs later over the network, and why that one never blocks anything.
