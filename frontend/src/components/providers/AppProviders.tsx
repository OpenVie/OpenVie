"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { Toaster } from "react-hot-toast";
import { PageTitle } from "@/components/app/PageTitle";
import { StoreProvider } from "@/components/providers/StoreProvider";

export function AppProviders({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => new QueryClient());

  return (
    <>
      <PageTitle />
      <QueryClientProvider client={queryClient}>
        <StoreProvider>{children}</StoreProvider>
        <Toaster position="top-right" />
      </QueryClientProvider>
    </>
  );
}
