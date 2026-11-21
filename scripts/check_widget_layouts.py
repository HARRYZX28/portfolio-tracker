# Fails if a widget layout uses a view type Android home-screen widgets can't show.
import re, sys, glob
ok = {"FrameLayout","LinearLayout","RelativeLayout","GridLayout","AnalogClock","Button","Chronometer","ImageButton","ImageView","ProgressBar","TextView","ViewFlipper","ListView","GridView","StackView","AdapterViewFlipper","ViewStub"}
bad = []
for f in glob.glob(sys.argv[1] + "/widget*.xml"):
    for tag in re.findall(r"<([A-Za-z.]+)[\s>/]", open(f).read()):
        if tag not in ok: bad.append(f"{f}: <{tag}>")
print("\n".join(bad) or "all widget views allowed"); sys.exit(1 if bad else 0)
