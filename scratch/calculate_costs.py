import locale
locale.setlocale(locale.LC_ALL, '')

# Claude 3 Opus Pricing ($/M tokens)
IN_PRICE = 15.0
OUT_PRICE = 75.0
USD_TO_INR = 84.0

data = [
    {"doc": "HR Steel SCN.pdf", "in_tok": 40091, "out_tok": 7237},
    {"doc": "1st RFD-08.pdf", "in_tok": 9515, "out_tok": 5821},
    {"doc": "novel_crypto_gst_notice.pdf", "in_tok": 6688, "out_tok": 4321},
]

print("| Document | Input Tokens | Output Tokens | Cost (USD) | Cost (INR) |")
print("|---|---|---|---|---|")

total_in = 0
total_out = 0
total_usd = 0.0

for row in data:
    in_tok = row["in_tok"]
    out_tok = row["out_tok"]
    
    cost_in_usd = (in_tok / 1_000_000) * IN_PRICE
    cost_out_usd = (out_tok / 1_000_000) * OUT_PRICE
    cost_usd = cost_in_usd + cost_out_usd
    cost_inr = cost_usd * USD_TO_INR
    
    total_in += in_tok
    total_out += out_tok
    total_usd += cost_usd
    
    print(f"| {row['doc']} | {in_tok:,} | {out_tok:,} | ${cost_usd:.4f} | ₹{cost_inr:.2f} |")

total_inr = total_usd * USD_TO_INR
print(f"| **TOTAL** | **{total_in:,}** | **{total_out:,}** | **${total_usd:.4f}** | **₹{total_inr:.2f}** |")

