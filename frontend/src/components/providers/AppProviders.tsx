"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Toaster } from "react-hot-toast";
import { PageTitle } from "@/components/app/PageTitle";
import { StoreProvider } from "@/components/providers/StoreProvider";

type Theme = "light" | "dark";

function currentTheme(): Theme {
  if (typeof window === "undefined") return "light";
  return document.documentElement.dataset.ovTheme === "dark" ? "dark" : "light";
}

function ThemeToaster() {
  const [theme, setTheme] = useState<Theme>(currentTheme);

  useEffect(() => {
    const syncTheme = () => setTheme(currentTheme());
    window.addEventListener("openvie:themechange", syncTheme);
    return () => window.removeEventListener("openvie:themechange", syncTheme);
  }, []);

  const dark = theme === "dark";
  return (
    <Toaster
      position="top-right"
      toastOptions={{
        style: {
          background: dark ? "#12161a" : "#ffffff",
          color: dark ? "#f2f5f0" : "#10150f",
          border: dark ? "1px solid rgb(255 255 255 / 0.14)" : "1px solid rgb(16 24 14 / 0.14)",
          borderRadius: "14px",
          boxShadow: dark ? "0 18px 42px -16px rgb(0 0 0 / 0.7)" : "0 18px 42px -16px rgb(16 24 14 / 0.28)",
        },
        success: { iconTheme: { primary: "#c8f53c", secondary: "#07090c" } },
        error: { iconTheme: { primary: "#ff4d55", secondary: dark ? "#12161a" : "#ffffff" } },
      }}
    />
  );
}

export function AppProviders({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => new QueryClient());

  return (
    <>
      <PageTitle />
      <QueryClientProvider client={queryClient}>
        <StoreProvider>{children}</StoreProvider>
        <ThemeToaster />
      </QueryClientProvider>
    </>
  );
}
