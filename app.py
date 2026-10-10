import datetime
from pathlib import Path

import pandas as pd
import streamlit as st

from calc import TAX_MODES, TAX_NONE, compute, export_excel
from marketplaces import (
    DEMO_COSTS, ApiError, aggregate_ozon, aggregate_wb, demo_data,
    fetch_ozon_operations, fetch_wb_rows,
)

st.set_page_config(page_title="Юнит-аналитика WB & Ozon", page_icon="📊", layout="wide")

COSTS_FILE = Path(__file__).with_name("cost_prices.csv")


# ---------------------------------------------------------------------------
# Себестоимость: хранение в CSV рядом с приложением
# ---------------------------------------------------------------------------

def load_costs() -> dict:
    if not COSTS_FILE.exists():
        return {}
    try:
        d = pd.read_csv(COSTS_FILE, dtype={"marketplace": str, "sku": str})
        return {(r.marketplace, r.sku): float(r.cost_price) for r in d.itertuples()}
    except Exception:
        return {}


def save_costs(table: pd.DataFrame) -> None:
    costs = load_costs()
    for r in table.itertuples():
        costs[(r.marketplace, r.sku)] = float(r.cost_price or 0)
    pd.DataFrame(
        [{"marketplace": m, "sku": s, "cost_price": c} for (m, s), c in costs.items()]
    ).to_csv(COSTS_FILE, index=False)


def build_cost_table(raw: pd.DataFrame, demo: bool) -> pd.DataFrame:
    saved = {} if demo else load_costs()
    t = raw[["marketplace", "sku", "product_name", "sales_qty"]].copy()
    if demo:
        t["cost_price"] = t["sku"].map(DEMO_COSTS).fillna(0.0)
    else:
        t["cost_price"] = [saved.get((m, s), 0.0) for m, s in zip(t["marketplace"], t["sku"])]
    t["ad_cost"] = 0.0
    return t.reset_index(drop=True)


def set_data(raw: pd.DataFrame, overhead: pd.DataFrame, demo: bool) -> None:
    raw = raw.copy()
    raw["sku"] = raw["sku"].astype(str)  # WB - числа, Ozon - строки: приводим к одному типу
    st.session_state.raw_data = raw.reset_index(drop=True)
    st.session_state.overhead = overhead.reset_index(drop=True)
    st.session_state.is_demo = demo
    st.session_state.cost_table = build_cost_table(st.session_state.raw_data, demo)
    st.session_state.editor_version = st.session_state.get("editor_version", 0) + 1


for key, default in (("raw_data", pd.DataFrame()), ("overhead", pd.DataFrame()),
                     ("is_demo", False), ("editor_version", 0)):
    st.session_state.setdefault(key, default)

# ---------------------------------------------------------------------------
# Сайдбар
# ---------------------------------------------------------------------------

st.title("📊 Автоматическая юнит-аналитика (WB / Ozon)")
st.caption("Прибыль, маржа и ROI по каждому SKU: комиссии, логистика, хранение, штрафы, реклама и налог")

with st.sidebar:
    st.header("🔑 Настройки")
    demo_mode = st.toggle("🧪 Демо-режим (без API)", value=False)

    wb_token = ozon_client_id = ozon_api_key = ""
    days = 30
    if not demo_mode:
        days = st.slider("Период анализа (дней):", 7, 90, 30)
        st.subheader("Wildberries")
        wb_token = st.text_input("Токен API «Статистика»", type="password")
        st.subheader("Ozon Seller")
        ozon_client_id = st.text_input("Client-Id")
        ozon_api_key = st.text_input("Api-Key", type="password")
    else:
        st.info("API-ключи не нужны, используются тестовые данные.")

    st.subheader("Налог")
    tax_mode = st.selectbox("Система налогообложения", TAX_MODES)
    tax_rate = st.number_input("Ставка, %", min_value=0.0, max_value=100.0,
                               value=6.0 if tax_mode != TAX_NONE else 0.0,
                               step=0.5, disabled=tax_mode == TAX_NONE)
    if tax_mode == TAX_NONE:
        tax_rate = 0.0

    fetch_btn = st.button("🧪 Загрузить демо-данные" if demo_mode else "🚀 Загрузить данные по API",
                          type="primary", width="stretch")

# ---------------------------------------------------------------------------
# Загрузка данных (только по нажатию кнопки)
# ---------------------------------------------------------------------------

if fetch_btn:
    if demo_mode:
        raw, oh = demo_data()
        set_data(raw, oh, demo=True)
        st.toast("Загружены демо-данные", icon="✅")
    elif not wb_token and not (ozon_client_id and ozon_api_key):
        st.warning("Введите токен WB и/или Client-Id + Api-Key Ozon.")
    else:
        parts, overheads = [], []
        with st.status("Загрузка данных с маркетплейсов…", expanded=True) as status:
            if wb_token:
                try:
                    rows = fetch_wb_rows(wb_token, days, on_status=status.write)
                    wb_df, wb_oh = aggregate_wb(rows)
                    status.write(f"Wildberries: строк отчёта - {len(rows)}, товаров - {len(wb_df)}")
                    parts.append(wb_df)
                    overheads.append(wb_oh)
                except ApiError as e:
                    st.error(f"Wildberries: {e}")
            if ozon_client_id and ozon_api_key:
                try:
                    ops = fetch_ozon_operations(ozon_client_id, ozon_api_key, days, on_status=status.write)
                    oz_df, oz_oh = aggregate_ozon(ops)
                    status.write(f"Ozon: операций - {len(ops)}, товаров - {len(oz_df)}")
                    parts.append(oz_df)
                    overheads.append(oz_oh)
                except ApiError as e:
                    st.error(f"Ozon: {e}")
            status.update(label="Загрузка завершена", state="complete")

        parts = [p for p in parts if not p.empty]
        if parts:
            set_data(pd.concat(parts, ignore_index=True),
                     pd.concat([o for o in overheads if not o.empty] or [pd.DataFrame(columns=["marketplace", "category", "amount"])],
                               ignore_index=True), demo=False)
            st.toast("Данные загружены", icon="🎉")
        else:
            st.warning("Данных за период нет (или все запросы завершились ошибкой).")

# ---------------------------------------------------------------------------
# Основной экран
# ---------------------------------------------------------------------------

if st.session_state.raw_data.empty:
    st.info("👈 Введите API-ключи в меню слева или включите «Демо-режим».")
    st.stop()

raw = st.session_state.raw_data
overhead = st.session_state.overhead
is_demo = st.session_state.is_demo

st.subheader("1. Себестоимость и реклама")
st.markdown("Укажите **себестоимость 1 шт.** и, если есть, **расходы на рекламу** за период по товару. "
            "Себестоимость можно сохранить - при следующей загрузке подставится сама.")

edited = st.data_editor(
    st.session_state.cost_table,
    key=f"editor_{st.session_state.editor_version}",
    disabled=["marketplace", "sku", "product_name", "sales_qty"],
    column_config={
        "marketplace": st.column_config.TextColumn("Площадка"),
        "sku": st.column_config.TextColumn("Артикул/SKU"),
        "product_name": st.column_config.TextColumn("Товар"),
        "sales_qty": st.column_config.NumberColumn("Продажи, шт. (чистые)"),
        "cost_price": st.column_config.NumberColumn("Себестоимость 1 шт, ₽", min_value=0.0, format="%.2f"),
        "ad_cost": st.column_config.NumberColumn("Реклама за период, ₽", min_value=0.0, format="%.2f"),
    },
    width="stretch",
    hide_index=True,
)

c1, c2 = st.columns([1, 2])
with c1:
    if not is_demo and st.button("💾 Сохранить себестоимость", width="stretch"):
        save_costs(edited)
        st.toast("Себестоимость сохранена", icon="💾")
with c2:
    with st.expander("📂 Загрузить себестоимость из CSV"):
        st.caption("Колонки: `sku`, `cost_price` (и по желанию `marketplace`).")
        up = st.file_uploader("CSV-файл", type="csv", label_visibility="collapsed")
        if up is not None and st.session_state.get("applied_upload") != (up.name, up.size):
            try:
                u = pd.read_csv(up, dtype={"sku": str})
                u["cost_price"] = pd.to_numeric(u["cost_price"], errors="coerce")
                table = edited.copy()
                if "marketplace" in u.columns:
                    m = {(r.marketplace, r.sku): r.cost_price for r in u.itertuples()}
                    new = [m.get((a, b)) for a, b in zip(table["marketplace"], table["sku"])]
                else:
                    m = dict(zip(u["sku"], u["cost_price"]))
                    new = [m.get(b) for b in table["sku"]]
                table["cost_price"] = [n if pd.notna(n) else o for n, o in zip(new, table["cost_price"])]
                st.session_state.cost_table = table
                st.session_state.editor_version += 1
                st.session_state.applied_upload = (up.name, up.size)
                st.rerun()
            except Exception as e:
                st.error(f"Не удалось прочитать CSV: {e}")

# ---------------------------------------------------------------------------
# Расчёт
# ---------------------------------------------------------------------------

df = compute(raw, edited, tax_mode, tax_rate)
overhead_total = float(overhead["amount"].sum()) if not overhead.empty else 0.0

total_rev = df["revenue"].sum()
profit_sku = df["net_profit"].sum()
profit_total = profit_sku - overhead_total
total_cost = df["total_cost"].sum()
margin = profit_total / total_rev * 100 if total_rev > 0 else float("nan")
roi = profit_total / total_cost * 100 if total_cost > 0 else float("nan")
loss_skus = int((df["net_profit"] < 0).sum())


def fmt_money(x: float) -> str:
    return f"{x:,.0f} ₽".replace(",", " ")


def fmt_pct(x: float) -> str:
    return "—" if pd.isna(x) else f"{x:.1f}%"


st.subheader("2. Сводка")
m1, m2, m3, m4, m5 = st.columns(5)
m1.metric("Выручка (за вычетом возвратов)", fmt_money(total_rev))
m2.metric("Чистая прибыль", fmt_money(profit_total), delta=f"{fmt_pct(margin)} маржа" if pd.notna(margin) else None)
m3.metric("ROI", fmt_pct(roi))
m4.metric("Общие расходы не по SKU", fmt_money(overhead_total))
m5.metric("Убыточных товаров", f"{loss_skus} шт.", delta_color="inverse")

zero_cost = df[(df["cost_price"] == 0) & (df["sales_qty"] > 0)]
if not zero_cost.empty:
    st.warning(f"⚠️ У {len(zero_cost)} товаров себестоимость равна 0 ₽ - прибыль по ним завышена.")
if tax_mode != TAX_NONE:
    st.caption(f"Налог: {tax_mode}, ставка {tax_rate:g}%. Для УСН «Доходы – Расходы» расчёт приблизительный "
               "(без минимального налога 1% и переноса убытков).")

by_mp = (df.groupby("marketplace")[["revenue", "commission", "logistics", "net_profit"]].sum()
         .rename(columns={"revenue": "Выручка", "commission": "Комиссия",
                          "logistics": "Логистика", "net_profit": "Прибыль по SKU"}))
st.dataframe(by_mp, width="stretch",
             column_config={c: st.column_config.NumberColumn(format="%.0f ₽") for c in by_mp.columns})

if not overhead.empty:
    with st.expander(f"Общие расходы не по SKU: {fmt_money(overhead_total)}"):
        st.caption("Реклама, хранение, платная приёмка, удержания и прочее, что маркетплейс не привязал к товару. "
                   "Вычитаются из итоговой прибыли.")
        st.dataframe(overhead.rename(columns={"marketplace": "Площадка", "category": "Статья", "amount": "Сумма, ₽"}),
                     width="stretch", hide_index=True,
                     column_config={"Сумма, ₽": st.column_config.NumberColumn(format="%.0f")})

# ---------------------------------------------------------------------------
# Отчёт по SKU
# ---------------------------------------------------------------------------

st.subheader("3. Отчёт по каждому SKU")
show = df.sort_values("net_profit", ascending=False)[[
    "marketplace", "sku", "product_name", "sales_qty", "revenue", "commission", "logistics",
    "storage", "penalties", "ad_cost", "tax", "total_cost", "net_profit", "margin_pct", "roi_pct", "profit_per_unit",
]]
st.dataframe(
    show,
    width="stretch",
    hide_index=True,
    column_config={
        "marketplace": "Площадка", "sku": "SKU", "product_name": "Наименование",
        "sales_qty": "Продажи (шт)",
        "revenue": st.column_config.NumberColumn("Выручка", format="%.0f ₽"),
        "commission": st.column_config.NumberColumn("Комиссия", format="%.0f ₽"),
        "logistics": st.column_config.NumberColumn("Логистика", format="%.0f ₽"),
        "storage": st.column_config.NumberColumn("Хранение", format="%.0f ₽"),
        "penalties": st.column_config.NumberColumn("Штрафы/прочее", format="%.0f ₽"),
        "ad_cost": st.column_config.NumberColumn("Реклама", format="%.0f ₽"),
        "tax": st.column_config.NumberColumn("Налог", format="%.0f ₽"),
        "total_cost": st.column_config.NumberColumn("Себестоимость", format="%.0f ₽"),
        "net_profit": st.column_config.NumberColumn("Прибыль", format="%.0f ₽"),
        "margin_pct": st.column_config.NumberColumn("Маржа", format="%.1f %%"),
        "roi_pct": st.column_config.NumberColumn("ROI", format="%.1f %%"),
        "profit_per_unit": st.column_config.NumberColumn("Прибыль/шт", format="%.0f ₽"),
    },
)

st.download_button(
    "📥 Скачать отчёт в Excel (.xlsx)",
    data=export_excel(df, overhead, tax_mode, tax_rate),
    file_name=f"unit_analytics_{datetime.date.today()}.xlsx",
    mime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    type="primary",
)
