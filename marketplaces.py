"""Загрузка и агрегация финансовых данных Wildberries и Ozon.

Модуль не зависит от Streamlit, поэтому его можно тестировать отдельно.
Все суммы в итоговых таблицах - положительные РАСХОДЫ (комиссия, логистика и т.д.),
выручка - с учётом возвратов (возврат вычитается).
"""
from __future__ import annotations

import datetime as dt
import time
from collections import defaultdict
from typing import Callable, Optional

import pandas as pd
import requests

StatusCb = Optional[Callable[[str], None]]

SKU_COLUMNS = [
    "marketplace", "sku", "product_name", "sales_qty",
    "revenue", "commission", "logistics", "storage", "penalties",
]
OVERHEAD_COLUMNS = ["marketplace", "category", "amount"]

WB_URL = "https://statistics-api.wildberries.ru/api/v5/supplier/reportDetailByPeriod"
WB_LIMIT = 100_000
WB_PAUSE = 61  # лимит WB: 1 запрос в минуту

OZON_URL = "https://api-seller.ozon.ru/v3/finance/transaction/list"
OZON_PAGE_SIZE = 1000
OZON_CHUNK_DAYS = 28  # Ozon принимает период не более 1 месяца за запрос


class ApiError(Exception):
    """Понятная пользователю ошибка API."""


def _empty_sku() -> pd.DataFrame:
    return pd.DataFrame(columns=SKU_COLUMNS)


def _empty_overhead() -> pd.DataFrame:
    return pd.DataFrame(columns=OVERHEAD_COLUMNS)


def _sleep(seconds: float, on_status: StatusCb = None, reason: str = "") -> None:
    if on_status and seconds >= 5:
        on_status(f"{reason} Пауза {int(seconds)} с…".strip())
    time.sleep(seconds)


def _request(method: str, url: str, *, headers: dict, params: dict | None = None,
             json: dict | None = None, retries: int = 4, rate_pause: float = 61,
             on_status: StatusCb = None, timeout: int = 90):
    """HTTP-запрос с повторами при 429/5xx и сетевых сбоях. Возвращает JSON или None (204)."""
    for attempt in range(1, retries + 1):
        last = attempt == retries
        try:
            resp = requests.request(method, url, headers=headers, params=params,
                                    json=json, timeout=timeout)
        except requests.RequestException as exc:
            if last:
                raise ApiError(f"Сетевая ошибка: {exc}") from exc
            _sleep(5 * attempt, on_status, "Сетевая ошибка, повторяю.")
            continue

        code = resp.status_code
        if code == 200:
            return resp.json()
        if code == 204:
            return None
        if code in (401, 403):
            raise ApiError("Доступ запрещён (401/403): проверьте ключ и его права.")
        if code == 429 or code >= 500:
            if last:
                raise ApiError(f"API недоступен или превышен лимит (HTTP {code}).")
            wait = rate_pause if code == 429 else 5 * attempt
            _sleep(wait, on_status, f"HTTP {code}.")
            continue
        raise ApiError(f"HTTP {code}: {resp.text[:300]}")
    return None


# ---------------------------------------------------------------------------
# Вспомогательные функции для pandas
# ---------------------------------------------------------------------------

def _num(df: pd.DataFrame, col: str) -> pd.Series:
    if col not in df.columns:
        return pd.Series(0.0, index=df.index)
    return pd.to_numeric(df[col], errors="coerce").fillna(0.0)


def _txt(df: pd.DataFrame, col: str) -> pd.Series:
    if col not in df.columns:
        return pd.Series("", index=df.index)
    return df[col].fillna("").astype(str).str.strip()


def _first_nonempty(values) -> str:
    return next((v for v in values if v), "")


# ---------------------------------------------------------------------------
# WILDBERRIES
# ---------------------------------------------------------------------------

def fetch_wb_rows(token: str, days: int = 30, on_status: StatusCb = None) -> list[dict]:
    """Отчёт о продажах по реализации (v5) со всеми страницами (rrdid)."""
    today = dt.date.today()
    params = {
        "dateFrom": (today - dt.timedelta(days=days)).isoformat(),
        "dateTo": today.isoformat(),
        "limit": WB_LIMIT,
        "rrdid": 0,
    }
    rows: list[dict] = []
    page = 0
    while True:
        page += 1
        if on_status:
            on_status(f"Wildberries: запрос страницы {page}…")
        data = _request("GET", WB_URL, headers={"Authorization": token},
                        params=params, rate_pause=WB_PAUSE, on_status=on_status)
        if not data:
            break
        rows.extend(data)
        if len(data) < WB_LIMIT:
            break
        params["rrdid"] = max(int(r.get("rrd_id", 0)) for r in data)
        _sleep(WB_PAUSE, on_status, "Лимит WB - 1 запрос в минуту.")
    return rows


def aggregate_wb(rows: list[dict]) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Строки отчёта WB -> (таблица по SKU, расходы без привязки к SKU)."""
    if not rows:
        return _empty_sku(), _empty_overhead()

    df = pd.DataFrame(rows)
    oper = _txt(df, "supplier_oper_name")
    # Продажа = +1, Возврат = -1, остальные строки (логистика, штрафы...) выручки не дают
    sign = oper.map({"Продажа": 1.0, "Возврат": -1.0}).fillna(0.0)

    subject, article = _txt(df, "subject_name"), _txt(df, "sa_name")
    name = (subject + " (" + article + ")").where(article != "", subject).str.strip()

    rec = pd.DataFrame({
        "nm_id": _num(df, "nm_id").astype("int64"),
        "oper": oper,
        "name": name,
        "sales_qty": sign * _num(df, "quantity"),
        "revenue": sign * _num(df, "retail_amount"),
        "payout": sign * _num(df, "ppvz_for_pay"),
        "logistics": _num(df, "delivery_rub"),
        "storage": _num(df, "storage_fee") + _num(df, "acceptance"),
        # штрафы + удержания - доплаты
        "penalties": _num(df, "penalty") + _num(df, "deduction") - _num(df, "additional_payment"),
    })
    # комиссия WB (вместе с эквайрингом) = то, что покупатель заплатил, минус то, что перечислят вам
    rec["commission"] = rec["revenue"] - rec["payout"]

    has_sku = rec["nm_id"] != 0
    sku_df = (
        rec[has_sku]
        .groupby("nm_id")
        .agg(product_name=("name", _first_nonempty), sales_qty=("sales_qty", "sum"),
             revenue=("revenue", "sum"), commission=("commission", "sum"),
             logistics=("logistics", "sum"), storage=("storage", "sum"),
             penalties=("penalties", "sum"))
        .reset_index()
    )
    sku_df["sku"] = sku_df["nm_id"].astype(str)
    sku_df["product_name"] = sku_df["product_name"].where(
        sku_df["product_name"] != "", "Товар " + sku_df["sku"])
    sku_df["sales_qty"] = sku_df["sales_qty"].round().astype(int)
    sku_df["marketplace"] = "Wildberries"
    sku_df = sku_df[SKU_COLUMNS]

    no_sku = rec[~has_sku].copy()
    no_sku["amount"] = no_sku["logistics"] + no_sku["storage"] + no_sku["penalties"]
    no_sku["category"] = no_sku["oper"].replace("", "Прочее")
    overhead = no_sku.groupby("category", as_index=False)["amount"].sum()
    overhead = overhead[overhead["amount"].abs() > 0.005]
    overhead["marketplace"] = "Wildberries"
    return sku_df, overhead[OVERHEAD_COLUMNS]


# ---------------------------------------------------------------------------
# OZON
# ---------------------------------------------------------------------------

def _ozon_ts(d: dt.datetime) -> str:
    return d.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def fetch_ozon_operations(client_id: str, api_key: str, days: int = 30,
                          on_status: StatusCb = None) -> list[dict]:
    """Все финансовые операции Ozon: период режется на куски <= 28 дней, листаются все страницы."""
    headers = {"Client-Id": client_id, "Api-Key": api_key, "Content-Type": "application/json"}
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    start = (now - dt.timedelta(days=days)).replace(hour=0, minute=0, second=0)

    chunks, cur = [], start
    while cur < now:
        nxt = min(cur + dt.timedelta(days=OZON_CHUNK_DAYS), now)
        chunks.append((cur, nxt))
        cur = nxt

    operations: dict[int | str, dict] = {}  # дедупликация по operation_id на стыках периодов
    for idx, (c_from, c_to) in enumerate(chunks, 1):
        c_to_req = c_to if idx == len(chunks) else c_to - dt.timedelta(seconds=1)
        page = 1
        while True:
            if on_status:
                on_status(f"Ozon: период {idx}/{len(chunks)}, страница {page}…")
            body = {
                "filter": {
                    "date": {"from": _ozon_ts(c_from), "to": _ozon_ts(c_to_req)},
                    "operation_type": [],
                    "posting_number": "",
                    "transaction_type": "all",
                },
                "page": page,
                "page_size": OZON_PAGE_SIZE,
            }
            data = _request("POST", OZON_URL, headers=headers, json=body,
                            rate_pause=10, on_status=on_status) or {}
            result = data.get("result") or {}
            for op in result.get("operations") or []:
                operations[op.get("operation_id", id(op))] = op
            if page >= int(result.get("page_count") or 1):
                break
            page += 1
    return list(operations.values())


def _classify_service(name: str) -> str:
    n = (name or "").lower()
    if any(k in n for k in ("storage", "placement", "stock")):
        return "storage"
    if any(k in n for k in ("penalt", "fine")):
        return "penalties"
    return "logistics"


def aggregate_ozon(operations: list[dict]) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Операции Ozon -> (таблица по SKU, расходы без привязки к SKU: реклама, хранение и т.п.).

    Знаки в API Ozon: начисления положительные, комиссия/услуги отрицательные.
    Остаток, не попавший ни в одно поле (компенсации и т.п.), попадает в «штрафы/прочее»,
    поэтому сумма по всем строкам всегда сходится с суммой `amount` из API.
    """
    if not operations:
        return _empty_sku(), _empty_overhead()

    def new_rec():
        return {"product_name": "", "sales_qty": 0, "revenue": 0.0, "commission": 0.0,
                "logistics": 0.0, "storage": 0.0, "penalties": 0.0}

    per_sku: dict[str, dict] = defaultdict(new_rec)
    overhead: dict[str, float] = defaultdict(float)

    for op in operations:
        amount = float(op.get("amount") or 0)
        items = [i for i in (op.get("items") or []) if i.get("sku")]

        if not items:  # реклама, хранение, штрафы, компенсации без товара
            category = op.get("operation_type_name") or op.get("operation_type") or "Прочее"
            overhead[category] += -amount
            continue

        accruals = float(op.get("accruals_for_sale") or 0)
        sale_comm = float(op.get("sale_commission") or 0)
        deliv = float(op.get("delivery_charge") or 0) + float(op.get("return_delivery_charge") or 0)
        services = op.get("services") or []

        commission = -sale_comm
        logistics = -deliv
        storage = penalties = 0.0
        for srv in services:
            cost = -float(srv.get("price") or 0)
            kind = _classify_service(srv.get("name", ""))
            if kind == "storage":
                storage += cost
            elif kind == "penalties":
                penalties += cost
            else:
                logistics += cost

        known = accruals + sale_comm + deliv + sum(float(s.get("price") or 0) for s in services)
        penalties += -(amount - known)  # неразнесённый остаток

        qty = 1 if accruals > 0 else (-1 if accruals < 0 else 0)  # возврат: accruals < 0
        n = len(items)
        for it in items:
            rec = per_sku[str(it["sku"])]
            rec["product_name"] = rec["product_name"] or str(it.get("name") or "")
            rec["sales_qty"] += qty
            rec["revenue"] += accruals / n
            rec["commission"] += commission / n
            rec["logistics"] += logistics / n
            rec["storage"] += storage / n
            rec["penalties"] += penalties / n

    sku_df = pd.DataFrame(
        [{"sku": sku, "marketplace": "Ozon", **rec} for sku, rec in per_sku.items()])
    if not sku_df.empty:
        sku_df["product_name"] = sku_df["product_name"].where(
            sku_df["product_name"] != "", "Товар " + sku_df["sku"])
        sku_df = sku_df[SKU_COLUMNS]
    else:
        sku_df = _empty_sku()

    overhead_df = pd.DataFrame(
        [{"marketplace": "Ozon", "category": k, "amount": v}
         for k, v in overhead.items() if abs(v) > 0.005], columns=OVERHEAD_COLUMNS)
    return sku_df, overhead_df


# ---------------------------------------------------------------------------
# ДЕМО
# ---------------------------------------------------------------------------

DEMO_COSTS = {"101123": 1200.0, "101124": 350.0, "202301": 180.0, "202302": 450.0}


def demo_data() -> tuple[pd.DataFrame, pd.DataFrame]:
    sku = pd.DataFrame([
        ("Wildberries", "101123", "Худи Оверсайз Черное", 120, 300000, 57000, 24000, 3000, 0),
        ("Wildberries", "101124", "Футболка Базовая Белая", 250, 200000, 38000, 20000, 1500, 500),
        ("Ozon", "202301", "Кружка Керамическая 350мл", 80, 48000, 9600, 7200, 800, 0),
        ("Ozon", "202302", "Термокружка Steel 500мл", 45, 67500, 12150, 4950, 500, 0),
    ], columns=SKU_COLUMNS)
    overhead = pd.DataFrame([
        ("Wildberries", "Удержание (реклама)", 12000.0),
        ("Ozon", "Оплата за клик", 4500.0),
    ], columns=OVERHEAD_COLUMNS)
    return sku, overhead
