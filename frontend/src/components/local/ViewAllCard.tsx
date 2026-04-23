'use client';

import React from 'react';
import { ArrowLeft } from 'lucide-react';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { useLanguage } from '@/contexts/LanguageContext';

interface ViewAllCardProps {
  count: number;
  itemName: string;
  itemNameEn: string;
  showAll: boolean;
  onShowAllChange: (show: boolean) => void;
  children: React.ReactNode;
}

export function ViewAllCard({ count, itemName, itemNameEn, showAll, onShowAllChange, children }: ViewAllCardProps) {
  const { language } = useLanguage();
  const isArabic = language === 'ar';

  return (
    <>
      {/* View All Card */}
      <button
        onClick={() => onShowAllChange(true)}
        className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
      >
        <div className="aspect-square rounded-xl bg-gray-50 border-2 border-dashed border-gray-200 flex flex-col items-center justify-center gap-3 group-hover:border-gray-300 group-hover:bg-gray-100 transition-all">
          <div className="w-12 h-12 rounded-full bg-white shadow-sm flex items-center justify-center">
            <ArrowLeft className="w-5 h-5 text-gray-400 rotate-180" />
          </div>
          <span className="text-sm font-medium text-gray-500 group-hover:text-gray-700">
            {isArabic ? 'عرض الكل' : 'Show All'}
          </span>
          <span className="text-xs text-gray-400">
            {count} {isArabic ? itemName : itemNameEn}
          </span>
        </div>
      </button>

      {/* All Items Sheet */}
      <Sheet open={showAll} onOpenChange={onShowAllChange}>
        <SheetContent side="bottom" className="h-[85vh] rounded-t-2xl">
          <SheetHeader className="pb-4">
            <SheetTitle>{isArabic ? `جميع ${itemName}` : `All ${itemNameEn}`}</SheetTitle>
          </SheetHeader>
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4 overflow-y-auto pb-4">
            {children}
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}
