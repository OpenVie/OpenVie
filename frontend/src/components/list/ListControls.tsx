// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

"use client";

import { CalendarDays, ChevronDown, ChevronLeft, ChevronRight, Check, Search } from "lucide-react";
import { Children, isValidElement, useEffect, useRef, useState, type ReactNode } from "react";
import { useFormatter, useLocale, useTranslations } from "next-intl";

import { useDebouncedValue } from "@/hooks/useDebouncedValue";
import { cn } from "@/lib/utils";

export function SearchField({
  value,
  onChange,
  placeholder,
  label,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  label?: string;
}) {
  const t = useTranslations("ListControls");
  return (
    <div className="ov-fld" style={{ flex: 1, minWidth: 160 }}>
      <span className="ov-fl">{label ?? t("searchRecords")}</span>
      <label className="ov-sfi">
        <Search className="size-[17px] shrink-0" aria-hidden="true" />
        <input
          type="text"
          aria-label={label ?? t("searchRecords")}
          value={value}
          maxLength={200}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder ?? t("search")}
        />
      </label>
    </div>
  );
}

export function UrlSearchField({
  initialValue,
  onDebouncedChange,
  placeholder,
  label,
}: {
  initialValue: string;
  onDebouncedChange: (value: string) => void;
  placeholder?: string;
  label?: string;
}) {
  const [value, setValue] = useState(initialValue);
  const debounced = useDebouncedValue(value, 300);
  useEffect(() => {
    if (debounced !== initialValue) onDebouncedChange(debounced);
  }, [debounced, initialValue, onDebouncedChange]);
  return <SearchField value={value} onChange={setValue} placeholder={placeholder} label={label} />;
}

export function FilterSelect({
  label,
  value,
  onChange,
  children,
  width,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  children: ReactNode;
  width?: number;
}) {
  const options = Children.toArray(children).flatMap((child) => {
    if (!isValidElement(child)) return [];
    const option = child as React.ReactElement<{ value?: string; children?: ReactNode }>;
    return [{ value: option.props.value ?? String(option.props.children), label: option.props.children }];
  });

  const containerRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const selectedLabel = options.find((o) => o.value === value)?.label ?? "";

  useEffect(() => {
    const close = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, []);

  return (
    <div className="ov-fld" ref={containerRef} style={width ? { width } : undefined}>
      <span className="ov-fl">{label}</span>
      <div className="ov-dd">
        <button
          type="button"
          className="ov-sl"
          aria-expanded={open}
          aria-haspopup="listbox"
          onClick={() => setOpen((c) => !c)}
        >
          <span>{selectedLabel}</span>
          <ChevronDown className="size-3.5 shrink-0 transition-transform" style={open ? { transform: "rotate(180deg)" } : undefined} />
        </button>
        {open && (
          <ul className="ov-ddm" role="listbox">
            {options.map((option) => (
              <li
                key={option.value}
                role="option"
                aria-selected={option.value === value}
                tabIndex={0}
                className="ov-opt"
                onClick={() => { onChange(option.value); setOpen(false); }}
                onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { onChange(option.value); setOpen(false); } }}
              >
                {option.label}
                <Check className="size-4" />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function parseDate(value: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) return null;
  return new Date(year, month - 1, day);
}

function toDateValue(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function isSameDay(first: Date, second: Date): boolean {
  return first.getFullYear() === second.getFullYear()
    && first.getMonth() === second.getMonth()
    && first.getDate() === second.getDate();
}

function DatePicker({
  label,
  value,
  min,
  onChange,
  align,
}: {
  label: string;
  value: string;
  min?: string;
  onChange: (value: string) => void;
  align: "left" | "right";
}) {
  const t = useTranslations("ListControls");
  const locale = useLocale();
  const containerRef = useRef<HTMLDivElement>(null);
  const selected = parseDate(value);
  const today = new Date();
  const [open, setOpen] = useState(false);
  const [calendarView, setCalendarView] = useState<"days" | "months" | "years">("days");
  const [visibleMonth, setVisibleMonth] = useState(() => {
    const initial = parseDate(value) ?? today;
    return new Date(initial.getFullYear(), initial.getMonth(), 1);
  });
  const [yearPageStart, setYearPageStart] = useState(() => {
    const initial = parseDate(value) ?? today;
    return initial.getFullYear() - 5;
  });

  useEffect(() => {
    const closeWhenClickingAway = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", closeWhenClickingAway);
    return () => document.removeEventListener("mousedown", closeWhenClickingAway);
  }, []);

  const monthLabel = new Intl.DateTimeFormat(locale, { month: "long", year: "numeric" }).format(visibleMonth);
  const weekdayLabels = Array.from({ length: 7 }, (_, index) => new Intl.DateTimeFormat(locale, { weekday: "short" }).format(new Date(2023, 0, index + 1)));
  const firstVisibleDay = new Date(visibleMonth.getFullYear(), visibleMonth.getMonth(), 1 - visibleMonth.getDay());
  const displayValue = selected
    ? new Intl.DateTimeFormat(locale, { month: "2-digit", day: "2-digit", year: "numeric" }).format(selected)
    : "mm/dd/yyyy";
  const monthNames = Array.from({ length: 12 }, (_, index) => new Intl.DateTimeFormat(locale, { month: "short" }).format(new Date(2026, index, 1)));
  const calendarTitle = calendarView === "days"
    ? monthLabel
    : calendarView === "months"
      ? String(visibleMonth.getFullYear())
      : `${yearPageStart}–${yearPageStart + 11}`;
  const previousLabel = calendarView === "days" ? "Previous month" : calendarView === "months" ? "Previous year" : "Previous years";
  const nextLabel = calendarView === "days" ? "Next month" : calendarView === "months" ? "Next year" : "Next years";

  const moveCalendar = (direction: -1 | 1) => {
    if (calendarView === "days") {
      setVisibleMonth((current) => new Date(current.getFullYear(), current.getMonth() + direction, 1));
    } else if (calendarView === "months") {
      setVisibleMonth((current) => new Date(current.getFullYear() + direction, current.getMonth(), 1));
    } else {
      setYearPageStart((current) => current + direction * 12);
    }
  };

  return (
    <div ref={containerRef} className="ov-dd" style={{ minWidth: 150 }}>
      <button
        type="button"
        aria-expanded={open}
        aria-haspopup="dialog"
        className="ov-sl"
        onClick={() => {
          if (!open) {
            const initial = selected ?? today;
            setVisibleMonth(new Date(initial.getFullYear(), initial.getMonth(), 1));
            setYearPageStart(initial.getFullYear() - 5);
            setCalendarView("days");
          }
          setOpen((current) => !current);
        }}
      >
        <span className={selected ? undefined : "ov-ph"}>{displayValue}</span>
        <CalendarDays className="size-4 shrink-0" />
      </button>
      {open && (
        <div
          role="dialog"
          aria-label={label}
          className={cn("ov-ddm ov-cal", align === "right" && "ov-ddm-rt")}
        >
          <div className="ov-cal-header">
            <button
              type="button"
              className="ov-cal-month"
              onClick={() => setCalendarView((current) => current === "days" ? "months" : current === "months" ? "years" : "days")}
              aria-label="Change month or year"
            >
              {calendarTitle}
            </button>
            <div className="flex gap-1">
              <button type="button" aria-label={previousLabel} onClick={() => moveCalendar(-1)} className="ov-cal-nav"><ChevronLeft className="size-4" /></button>
              <button type="button" aria-label={nextLabel} onClick={() => moveCalendar(1)} className="ov-cal-nav"><ChevronRight className="size-4" /></button>
            </div>
          </div>
          {calendarView === "days" ? (
            <div className="ov-cal-grid">
              {weekdayLabels.map((weekday) => <span key={weekday} className="ov-cal-weekday">{weekday}</span>)}
              {Array.from({ length: 42 }, (_, index) => {
                const date = new Date(firstVisibleDay.getFullYear(), firstVisibleDay.getMonth(), firstVisibleDay.getDate() + index);
                const dateValue = toDateValue(date);
                const disabled = Boolean(min && dateValue < min);
                const selectedDay = Boolean(selected && isSameDay(date, selected));
                const todayDay = isSameDay(date, today);
                return (
                  <button
                    key={dateValue}
                    type="button"
                    disabled={disabled}
                    aria-pressed={selectedDay}
                    onClick={() => { onChange(dateValue); setOpen(false); }}
                    className={cn(
                      "ov-cal-day",
                      date.getMonth() !== visibleMonth.getMonth() && "ov-cal-day-other",
                      todayDay && !selectedDay && "ov-cal-day-today",
                      selectedDay && "ov-cal-day-selected",
                    )}
                  >
                    {date.getDate()}
                  </button>
                );
              })}
            </div>
          ) : calendarView === "months" ? (
            <div className="ov-cal-picker-grid" aria-label="Choose month">
              {monthNames.map((month, index) => (
                <button key={month} type="button" className={cn("ov-cal-picker-option", index === visibleMonth.getMonth() && "ov-cal-picker-option-selected")} onClick={() => { setVisibleMonth((current) => new Date(current.getFullYear(), index, 1)); setCalendarView("days"); }}>
                  {month}
                </button>
              ))}
            </div>
          ) : (
            <div className="ov-cal-picker-grid" aria-label="Choose year">
              {Array.from({ length: 12 }, (_, index) => yearPageStart + index).map((year) => (
                <button key={year} type="button" className={cn("ov-cal-picker-option", year === visibleMonth.getFullYear() && "ov-cal-picker-option-selected")} onClick={() => { setVisibleMonth((current) => new Date(year, current.getMonth(), 1)); setCalendarView("months"); }}>
                  {year}
                </button>
              ))}
            </div>
          )}
          <div className="ov-cal-footer">
            <button type="button" onClick={() => { onChange(""); setOpen(false); }} className="ov-cal-link">{t("clear")}</button>
            <button type="button" onClick={() => { onChange(toDateValue(today)); setOpen(false); }} className="ov-cal-link">{t("today")}</button>
          </div>
        </div>
      )}
    </div>
  );
}

export function DateRangeFields({
  from,
  to,
  onFromChange,
  onToChange,
  prefix,
}: {
  from: string;
  to: string;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  prefix?: string;
}) {
  const t = useTranslations("ListControls");
  const rangePrefix = prefix ?? t("date");
  return <>
    <div className="ov-fld">
      <span className="ov-fl">{t("from", { prefix: rangePrefix })}</span>
      <DatePicker label={t("from", { prefix: rangePrefix })} value={from} onChange={onFromChange} align="left" />
    </div>
    <div className="ov-fld">
      <span className="ov-fl">{t("to", { prefix: rangePrefix })}</span>
      <DatePicker label={t("to", { prefix: rangePrefix })} value={to} min={from || undefined} onChange={onToChange} align="right" />
    </div>
  </>;
}

export function FilterPanel({ children }: { children: ReactNode }) {
  return (
    <div className="ov-filter-panel">
      {children}
    </div>
  );
}

export function ClearFiltersButton({ onClick, disabled }: { onClick: () => void; disabled?: boolean }) {
  const t = useTranslations("ListControls");
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="ov-gh"
      style={{ height: 42 }}
    >
      {t("clear")}
    </button>
  );
}

export function ResultsSummary({ total, refreshing }: { total: number; refreshing?: boolean }) {
  const t = useTranslations("ListControls");
  const format = useFormatter();
  return (
    <p className="ov-results" aria-live="polite">
      {t("results", { count: total, formattedCount: format.number(total) })}
      {refreshing ? <span style={{ marginLeft: 8, color: "#c8f53c" }}>{t("refreshing")}</span> : null}
    </p>
  );
}

function PageSizePicker({ value, onChange, ariaLabel }: { value: number; onChange: (value: number) => void; ariaLabel: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const sizes = [10, 20, 50];

  useEffect(() => {
    const closeWhenClickingAway = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", closeWhenClickingAway);
    return () => document.removeEventListener("mousedown", closeWhenClickingAway);
  }, []);

  return (
    <div className="ov-page-size" ref={containerRef}>
      <button
        type="button"
        className="ov-page-size-trigger"
        aria-expanded={open}
        aria-haspopup="listbox"
        onClick={() => setOpen((current) => !current)}
        onKeyDown={(event) => {
          if (event.key === "Escape") setOpen(false);
        }}
      >
        <span>{value}</span>
        <ChevronDown className="size-3.5" aria-hidden="true" />
      </button>
      {open && (
        <ul className="ov-page-size-menu" role="listbox" aria-label={ariaLabel}>
          {sizes.map((size) => (
            <li
              key={size}
              role="option"
              aria-selected={size === value}
              tabIndex={0}
              className="ov-page-size-option"
              onClick={() => { onChange(size); setOpen(false); }}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onChange(size);
                  setOpen(false);
                }
              }}
            >
              <span>{size}</span>
              {size === value && <Check className="size-4" aria-hidden="true" />}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function PaginationControls({
  page,
  size,
  total,
  onPageChange,
  onSizeChange,
}: {
  page: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
}) {
  const t = useTranslations("ListControls");
  const pages = Math.max(1, Math.ceil(total / size));
  return (
    <div className="ov-pagination">
      <div className="ov-pagination-left">
        <span>{t("rows")}</span>
        <PageSizePicker value={size} onChange={onSizeChange} ariaLabel={t("rowsPerPage")} />
      </div>
      <div className="ov-pagination-right">
        <span>{t("pageOf", { page, pages })}</span>
        <button type="button" className="ov-gh" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>{t("previous")}</button>
        <button type="button" className="ov-gh" disabled={page >= pages} onClick={() => onPageChange(page + 1)}>{t("next")}</button>
      </div>
    </div>
  );
}
