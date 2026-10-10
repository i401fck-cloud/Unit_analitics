"""Расчёт юнит-экономики и экспорт в Excel (с живыми формулами)."""
from __future__ import annotations

import io

import pandas as pd
from openpyxl import Workbook
from openpyxl.formatting.rule import CellIsRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter

TAX_NONE = "Без налога"
TAX_INCOME = "УСН «Доходы» (% от выручки)"
TAX_INCOME_EXPENSE = "УСН «Доходы – Расходы» (% от прибыли)"
TAX_MODES = [TAX_INCOME, TAX_INCOME_EXPENSE, TAX_NONE]


def compute(raw: pd.DataFrame, costs: pd.DataFrame, tax_mode: str, tax_rate: float) -> pd.DataFrame:
    """raw - данные API; costs - таблица с marketplace, sku, cost_price, ad_cost."""
    df = raw.merge(costs[["marketplace", "sku", "cost_price", "ad_cost"]],
                   on=["marketplace", "sku"], how="left")
    df[["cost_price", "ad_cost"]] = df[["cost_price", "ad_cost"]].fillna(0.0)

    df["total_cost"] = df["cost_price"] * df["sales_qty"]
    df["pre_tax_profit"] = (df["revenue"] - df["commission"] - df["logistics"]
                            - df["storage"] - df["penalties"] - df["ad_cost"] - df["total_cost"])

    rate = tax_rate / 100
    if tax_mode == TAX_INCOME:
        df["tax"] = df["revenue"].clip(lower=0) * rate
    elif tax_mode == TAX_INCOME_EXPENSE:
        df["tax"] = df["pre_tax_profit"].clip(lower=0) * rate
    else:
        df["tax"] = 0.0

    df["net_profit"] = df["pre_tax_profit"] - df["tax"]
    # деление на 0 даёт NaN (а не огромные числа)
    df["margin_pct"] = df["net_profit"] / df["revenue"].where(df["revenue"] > 0) * 100
    df["roi_pct"] = df["net_profit"] / df["total_cost"].where(df["total_cost"] > 0) * 100
    df["profit_per_unit"] = df["net_profit"] / df["sales_qty"].where(df["sales_qty"] != 0)
    return df


def export_excel(df: pd.DataFrame, overhead: pd.DataFrame, tax_mode: str, tax_rate: float) -> bytes:
    """Excel с формулами: можно менять себестоимость/рекламу прямо в файле."""
    wb = Workbook()
    ws = wb.active
    ws.title = "Юнит-экономика"

    headers = [
        "Площадка", "Артикул / SKU", "Наименование", "Продажи, шт.", "Выручка, ₽",
        "Комиссия, ₽", "Логистика, ₽", "Хранение, ₽", "Штрафы/прочее, ₽", "Реклама, ₽",
        "Налог, ₽", "Себестоимость 1 шт, ₽", "Себестоимость всего, ₽", "Чистая прибыль, ₽",
        "Маржа, %", "ROI, %", "Прибыль на 1 шт, ₽",
    ]
    ws.append(headers)

    head_font = Font(bold=True, color="FFFFFF")
    head_fill = PatternFill("solid", start_color="1F4E78", end_color="1F4E78")
    thin = Side(style="thin", color="D9D9D9")
    border = Border(left=thin, right=thin, top=thin, bottom=thin)
    input_font = Font(color="0000FF")  # синий = ячейки для ввода
    for c in range(1, len(headers) + 1):
        cell = ws.cell(row=1, column=c)
        cell.font, cell.fill, cell.border = head_font, head_fill, border
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    ws.row_dimensions[1].height = 32

    rate = tax_rate / 100
    for i, rec in enumerate(df.itertuples(index=False), start=2):
        if tax_mode == TAX_INCOME:
            tax_f = f"=MAX(E{i},0)*{rate}"
        elif tax_mode == TAX_INCOME_EXPENSE:
            tax_f = f"=MAX(E{i}-F{i}-G{i}-H{i}-I{i}-J{i}-M{i},0)*{rate}"
        else:
            tax_f = 0
        ws.append([
            rec.marketplace, rec.sku, rec.product_name, int(rec.sales_qty),
            round(rec.revenue, 2), round(rec.commission, 2), round(rec.logistics, 2),
            round(rec.storage, 2), round(rec.penalties, 2), round(rec.ad_cost, 2),
            tax_f, round(rec.cost_price, 2),
            f"=L{i}*D{i}",
            f"=E{i}-F{i}-G{i}-H{i}-I{i}-J{i}-K{i}-M{i}",
            f'=IF(E{i}>0,N{i}/E{i},"")',
            f'=IF(M{i}>0,N{i}/M{i},"")',
            f'=IF(D{i}<>0,N{i}/D{i},"")',
        ])

    last = len(df) + 1
    total = last + 1
    ws.cell(row=total, column=1, value="ИТОГО")
    for col in (4, 5, 6, 7, 8, 9, 10, 11, 13, 14):
        letter = get_column_letter(col)
        ws.cell(row=total, column=col, value=f"=SUM({letter}2:{letter}{last})")
    ws.cell(row=total, column=15, value=f'=IF(E{total}>0,N{total}/E{total},"")')
    ws.cell(row=total, column=16, value=f'=IF(M{total}>0,N{total}/M{total},"")')

    # Общие расходы (не по SKU) и итоговая прибыль
    ws.cell(row=total + 2, column=1, value="Прочие расходы не по SKU (см. лист «Общие расходы»)")
    ws.cell(row=total + 2, column=14, value=0)
    ws.cell(row=total + 3, column=1, value="ЧИСТАЯ ПРИБЫЛЬ после общих расходов")
    ws.cell(row=total + 3, column=14, value=f"=N{total}-N{total + 2}")
    for r in (total, total + 3):
        for c in range(1, len(headers) + 1):
            ws.cell(row=r, column=c).font = Font(bold=True)

    money = '#,##0 "₽";[Red]-#,##0 "₽"'
    for r in range(2, total + 4):
        for c in range(1, len(headers) + 1):
            cell = ws.cell(row=r, column=c)
            if r <= total:
                cell.border = border
            if c in (5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17):
                cell.number_format = money
                cell.alignment = Alignment(horizontal="right")
            elif c in (15, 16):
                cell.number_format = "0.0%"
                cell.alignment = Alignment(horizontal="right")
            elif c == 4:
                cell.number_format = "#,##0"
        if r <= last:
            for c in (10, 12):  # реклама и себестоимость 1 шт - ввод
                ws.cell(row=r, column=c).font = input_font

    green = PatternFill("solid", start_color="E2EFDA", end_color="E2EFDA")
    red = PatternFill("solid", start_color="FCE4D6", end_color="FCE4D6")
    ws.conditional_formatting.add(f"N2:N{last}", CellIsRule(operator="lessThan", formula=["0"], fill=red))
    ws.conditional_formatting.add(f"N2:N{last}", CellIsRule(operator="greaterThanOrEqual", formula=["0"], fill=green))

    widths = [14, 16, 38, 12, 14, 14, 14, 13, 16, 13, 13, 18, 18, 16, 11, 11, 16]
    for idx, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(idx)].width = w
    ws.freeze_panes = "D2"
    ws.auto_filter.ref = f"A1:{get_column_letter(len(headers))}{last}"

    note = ws.cell(row=total + 5, column=1,
                   value=f"Налог: {tax_mode}, ставка {tax_rate:g}%. Синие ячейки (реклама, себестоимость) можно менять - всё пересчитается.")
    note.font = Font(italic=True, color="7F7F7F")

    # Лист общих расходов
    ws2 = wb.create_sheet("Общие расходы")
    ws2.append(["Площадка", "Статья", "Сумма, ₽"])
    for c in range(1, 4):
        ws2.cell(row=1, column=c).font = head_font
        ws2.cell(row=1, column=c).fill = head_fill
    for rec in overhead.itertuples(index=False):
        ws2.append([rec.marketplace, rec.category, round(rec.amount, 2)])
    # итог общих расходов подтягивается на первый лист
    if not overhead.empty:
        n = len(overhead) + 1
        ws2.cell(row=n + 2, column=2, value="ИТОГО").font = Font(bold=True)
        ws2.cell(row=n + 2, column=3, value=f"=SUM(C2:C{n})").font = Font(bold=True)
        ws["N" + str(total + 2)] = f"='Общие расходы'!C{n + 2}"
    for r in range(2, ws2.max_row + 1):
        ws2.cell(row=r, column=3).number_format = money
    ws2.column_dimensions["A"].width = 14
    ws2.column_dimensions["B"].width = 50
    ws2.column_dimensions["C"].width = 16

    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()
