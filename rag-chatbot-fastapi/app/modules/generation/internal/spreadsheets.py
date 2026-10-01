from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal
from io import BytesIO
from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator


class CalculationError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class NormalizedColumn:
    name: str
    inferred_type: Literal["integer", "decimal", "date", "datetime", "boolean", "string"]


@dataclass(frozen=True, slots=True)
class NormalizedRow:
    row_number: int
    cell_range: str
    values: dict[str, Any]
    formula_columns: tuple[str, ...] = ()
    formula_expressions: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class NormalizedTable:
    table_id: str
    sheet_name: str
    cell_range: str
    columns: tuple[NormalizedColumn, ...]
    rows: tuple[NormalizedRow, ...]


class TypedFilter(BaseModel):
    column: str
    operator: Literal["eq", "ne", "gt", "gte", "lt", "lte", "between"]
    value: str | int | float | bool
    end_value: str | int | float | bool | None = None

    @model_validator(mode="after")
    def validate_range(self) -> TypedFilter:
        if self.operator == "between" and self.end_value is None:
            raise ValueError("between filters require end_value")
        return self


class CalculationCommand(BaseModel):
    table_id: str
    operation: Literal["count", "sum", "average", "minimum", "maximum", "sort", "top", "bottom"]
    column: str | None = None
    group_by: str | None = None
    filters: tuple[TypedFilter, ...] = ()
    limit: int = Field(default=10, ge=1, le=100)

    @model_validator(mode="after")
    def validate_operation(self) -> CalculationCommand:
        if self.operation != "count" and not self.column:
            raise ValueError(f"{self.operation} requires a column")
        if self.group_by and self.operation in {"sort", "top", "bottom"}:
            raise ValueError("group_by is only supported with aggregations")
        return self


@dataclass(frozen=True, slots=True)
class CalculationResult:
    table_id: str
    operation: str
    value: Any
    row_count: int


def parquet_bytes(table: NormalizedTable) -> bytes:
    try:
        import polars as pl
    except ImportError as exc:
        raise CalculationError("Parquet dependency polars is not installed") from exc
    frame = pl.DataFrame([_safe_values(row.values) for row in table.rows], strict=False)
    output = BytesIO()
    frame.write_parquet(output)
    return output.getvalue()


class PolarsCalculationAdapter:
    """Executes a validated command; it never evaluates user code or spreadsheet formulas."""

    def execute(self, table: NormalizedTable, command: CalculationCommand) -> CalculationResult:
        if table.table_id != command.table_id:
            raise CalculationError("Calculation table selection is ambiguous or invalid")
        try:
            import polars as pl
        except ImportError as exc:
            raise CalculationError("Calculation dependency polars is not installed") from exc
        columns = {column.name: column for column in table.columns}
        referenced = {item.column for item in command.filters}
        if command.column:
            referenced.add(command.column)
        if command.group_by:
            referenced.add(command.group_by)
        unknown = referenced - columns.keys()
        if unknown:
            raise CalculationError(f"Unknown spreadsheet column: {sorted(unknown)[0]}")

        frame = pl.DataFrame([_safe_values(row.values) for row in table.rows], strict=False)
        for specification in command.filters:
            filter_value = _coerce(specification.value, columns[specification.column].inferred_type)
            expression = pl.col(specification.column)
            if specification.operator == "eq":
                predicate = expression == filter_value
            elif specification.operator == "ne":
                predicate = expression != filter_value
            elif specification.operator == "gt":
                predicate = expression > filter_value
            elif specification.operator == "gte":
                predicate = expression >= filter_value
            elif specification.operator == "lt":
                predicate = expression < filter_value
            elif specification.operator == "lte":
                predicate = expression <= filter_value
            else:
                end = _coerce(specification.end_value, columns[specification.column].inferred_type)
                predicate = expression.is_between(filter_value, end, closed="both")
            frame = frame.filter(predicate)

        row_count = frame.height
        column = command.column
        if command.operation in {"sort", "top", "bottom"}:
            assert column is not None
            descending = command.operation in {"top", "sort"}
            value: Any = frame.sort(column, descending=descending).head(command.limit).to_dicts()
        elif command.group_by:
            if command.operation == "count" and column is None:
                aggregation = pl.len().alias("value")
            else:
                assert column is not None
                aggregation = _aggregation(pl.col(column), command.operation).alias("value")
            value = (
                frame.group_by(command.group_by).agg(aggregation).sort(command.group_by).to_dicts()
            )
        elif command.operation == "count":
            value = row_count if column is None else frame.select(pl.col(column).count()).item()
        else:
            assert column is not None
            value = frame.select(_aggregation(pl.col(column), command.operation)).item()
        return CalculationResult(command.table_id, command.operation, value, row_count)

    def execute_parquet(self, data: bytes, command: CalculationCommand) -> CalculationResult:
        try:
            import polars as pl
        except ImportError as exc:
            raise CalculationError("Calculation dependency polars is not installed") from exc
        frame = pl.read_parquet(BytesIO(data))
        columns = tuple(
            NormalizedColumn(name, _polars_type_name(str(dtype)))
            for name, dtype in frame.schema.items()
        )
        rows = tuple(
            NormalizedRow(index, str(index), values)
            for index, values in enumerate(frame.to_dicts(), start=1)
        )
        table = NormalizedTable(command.table_id, "", "", columns, rows)
        return self.execute(table, command)


def _aggregation(expression: Any, operation: str) -> Any:
    if operation == "sum":
        return expression.sum()
    if operation == "average":
        return expression.mean()
    if operation == "minimum":
        return expression.min()
    if operation == "maximum":
        return expression.max()
    if operation == "count":
        return expression.count()
    raise CalculationError("Unsupported calculation operation")


def _coerce(value: Any, inferred_type: str) -> Any:
    try:
        if inferred_type == "integer":
            return int(value)
        if inferred_type == "decimal":
            return float(Decimal(str(value)))
        if inferred_type == "boolean":
            if isinstance(value, bool):
                return value
            if str(value).lower() in {"true", "1"}:
                return True
            if str(value).lower() in {"false", "0"}:
                return False
            raise ValueError
        if inferred_type == "date":
            return date.fromisoformat(str(value))
        if inferred_type == "datetime":
            return datetime.fromisoformat(str(value))
        return str(value)
    except (ValueError, TypeError) as exc:
        raise CalculationError(
            "Spreadsheet filter value does not match the column type"
        ) from exc


def _safe_values(values: dict[str, Any]) -> dict[str, Any]:
    return {
        name: float(value) if isinstance(value, Decimal) else value
        for name, value in values.items()
    }


def _polars_type_name(
    dtype: str,
) -> Literal["integer", "decimal", "date", "datetime", "boolean", "string"]:
    if dtype.startswith(("Int", "UInt")):
        return "integer"
    if dtype.startswith(("Float", "Decimal")):
        return "decimal"
    if dtype == "Date":
        return "date"
    if dtype.startswith("Datetime"):
        return "datetime"
    if dtype == "Boolean":
        return "boolean"
    return "string"
