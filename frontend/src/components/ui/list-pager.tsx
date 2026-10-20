"use client";

import { useState } from "react";
import { PaginationControls } from "@/components/list/ListControls";
import { cn } from "@/lib/utils";

const DEFAULT_PAGE_SIZE = 20;

/**
 * Pure pagination math so a asked-for page never overruns a shrinking list:
 * the displayed page is derived (clamped), so only click handlers mutate
 * state and no re-adjusting effect is needed.
 */
export function pagedWindow<T>(items: T[], requestedPage: number, pageSize = DEFAULT_PAGE_SIZE) {
  const pageCount = Math.max(1, Math.ceil(items.length / pageSize));
  const page = Math.min(Math.max(1, requestedPage), pageCount);
  const start = (page - 1) * pageSize;
  return {
    page,
    pageCount,
    total: items.length,
    slice: items.slice(start, start + pageSize),
  };
}

/**
 * Client-side paging state for lists the API returns as one unpaginated
 * array (members, workspaces, approval queues) — mirrors the page/size pair
 * the documents list page keeps in the URL.
 */
export function usePagedList<T>(items: T[], defaultPageSize = DEFAULT_PAGE_SIZE) {
  const [requestedPage, setRequestedPage] = useState(1);
  const [size, setSize] = useState(defaultPageSize);
  const window = pagedWindow(items, requestedPage, size);
  return { ...window, size, setPage: setRequestedPage, setSize };
}

/**
 * The app's canonical pagination bar (see documents page), used for client-
 * side paged lists. Hidden for lists that already fit on one page.
 */
export function ListPager({
  page,
  pageCount,
  size,
  total,
  onPageChange,
  onSizeChange,
  className,
}: {
  page: number;
  pageCount: number;
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  onSizeChange: (size: number) => void;
  className?: string;
}) {
  if (pageCount <= 1) return null;
  return (
    <div className={cn("border-t border-border/70", className)}>
      <PaginationControls page={page} size={size} total={total} onPageChange={onPageChange} onSizeChange={onSizeChange} />
    </div>
  );
}
