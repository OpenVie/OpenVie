import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  BEFORE_LOCALE_CHANGE_EVENT,
  localeDraftStorageKey,
  useLocaleChangeDraft,
} from "./useLocaleChangeDraft";

describe("useLocaleChangeDraft", () => {
  beforeEach(() => window.sessionStorage.clear());

  it("hands the latest form value to the next locale route mount", () => {
    const restore = vi.fn();
    const first = renderHook(
      ({ value }) => useLocaleChangeDraft("documents:test", value, restore),
      { initialProps: { value: { title: "Initial" } } },
    );

    first.rerender({ value: { title: "Unsaved document" } });
    act(() => window.dispatchEvent(new Event(BEFORE_LOCALE_CHANGE_EVENT)));
    first.unmount();

    const nextRestore = vi.fn();
    renderHook(() => useLocaleChangeDraft("documents:test", { title: "" }, nextRestore));

    expect(nextRestore).toHaveBeenCalledWith({ title: "Unsaved document" });
  });

  it("clears a saved or cancelled draft", () => {
    const { result } = renderHook(() => useLocaleChangeDraft("documents:test", { title: "Draft" }, vi.fn()));
    act(() => window.dispatchEvent(new Event(BEFORE_LOCALE_CHANGE_EVENT)));
    expect(window.sessionStorage.getItem(localeDraftStorageKey("documents:test"))).not.toBeNull();

    act(() => result.current());

    expect(window.sessionStorage.getItem(localeDraftStorageKey("documents:test"))).toBeNull();
  });

  it("preserves rich HTML fields across a locale remount", () => {
    const restore = vi.fn();
    const value = { description: "Document summary", descriptionHtml: "<h2>Summary</h2><ul><li>Java</li></ul>" };
    const first = renderHook(() => useLocaleChangeDraft("documents:rich-doc", value, vi.fn()));
    act(() => window.dispatchEvent(new Event(BEFORE_LOCALE_CHANGE_EVENT)));
    first.unmount();
    renderHook(() => useLocaleChangeDraft("documents:rich-doc", { description: "", descriptionHtml: null as string | null }, restore));
    expect(restore).toHaveBeenCalledWith(value);
  });

  it("ignores and removes malformed stored drafts", () => {
    const key = localeDraftStorageKey("documents:test");
    window.sessionStorage.setItem(key, "not-json");
    const restore = vi.fn();

    renderHook(() => useLocaleChangeDraft("documents:test", { title: "" }, restore));

    expect(restore).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem(key)).toBeNull();
  });
});
