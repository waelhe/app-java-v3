'use client';

import React from 'react';
import { Newspaper, AlertCircle, Info, CheckCircle, Bell } from 'lucide-react';
import { motion } from 'framer-motion';
import { useLanguage } from '@/contexts/LanguageContext';

interface NewsItemData {
  id: string;
  text: string;
  textEn: string;
  type: 'news' | 'alert' | 'info' | 'success';
}

const newsItems: NewsItemData[] = [
  {
    id: '1',
    text: '⚡ انقطاع الكهرباء عن حي المزة لأعمال صيانة حتى الساعة 6 مساءً',
    textEn: '⚡ Power outage in Mezzeh district for maintenance until 6 PM',
    type: 'alert'
  },
  {
    id: '2',
    text: '✅ افتتاح مركز خدمات جديد في منطقة البرامكة',
    textEn: '✅ New service center opened in Barameka area',
    type: 'success'
  },
  {
    id: '3',
    text: '💧 تحديث: عودة الماء لجميع الأحياء بعد انتهاء أعمال الصيانة',
    textEn: '💧 Update: Water restored to all neighborhoods after maintenance',
    type: 'info'
  },
  {
    id: '4',
    text: '🌡️ تنبيه: ارتفاع درجات الحرارة المتوقعة غداً - يرجى الحيطة والحذر',
    textEn: '🌡️ Warning: High temperatures expected tomorrow - please take precautions',
    type: 'alert'
  },
  {
    id: '5',
    text: '⛽ توفر مادة المازوت في محطات الضاحية بسعر 800 ل.س',
    textEn: '⛽ Diesel available at Dahia stations for 800 S.P.',
    type: 'success'
  }
];

const getTypeConfig = (type: NewsItemData['type']) => {
  switch (type) {
    case 'alert':
      return {
        icon: AlertCircle,
        color: 'text-amber-600',
        bgColor: 'bg-amber-50',
        borderColor: 'border-amber-200'
      };
    case 'info':
      return {
        icon: Info,
        color: 'text-blue-600',
        bgColor: 'bg-blue-50',
        borderColor: 'border-blue-200'
      };
    case 'success':
      return {
        icon: CheckCircle,
        color: 'text-emerald-600',
        bgColor: 'bg-emerald-50',
        borderColor: 'border-emerald-200'
      };
    default:
      return {
        icon: Newspaper,
        color: 'text-gray-600',
        bgColor: 'bg-gray-50',
        borderColor: 'border-gray-200'
      };
  }
};

export default function NewsMarquee() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';

  return (
    <section className="py-2 bg-gray-50 border-y border-gray-100 overflow-hidden">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center gap-3">
          {/* Header */}
          <div className="flex items-center gap-1.5 shrink-0">
            <div className="p-1 bg-emerald-100 rounded-lg">
              <Bell className="w-3.5 h-3.5 text-emerald-600" />
            </div>
            <span className="font-bold text-gray-900 text-xs whitespace-nowrap">
              {isArabic ? 'آخر الأخبار' : 'Latest News'}
            </span>
          </div>

          {/* Marquee */}
          <div className="flex-1 overflow-hidden">
            <div 
              className="flex items-center gap-4 whitespace-nowrap"
              style={{
                animation: 'marquee 40s linear infinite'
              }}
            >
              {[...newsItems, ...newsItems].map((item, index) => {
                const config = getTypeConfig(item.type);

                return (
                  <div
                    key={`${item.id}-${index}`}
                    className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full ${config.bgColor} border ${config.borderColor}`}
                  >
                    <span className="text-xs text-gray-700">
                      {isArabic ? item.text : item.textEn}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </div>

      <style jsx global>{`
        @keyframes marquee {
          0% {
            transform: translateX(100%);
          }
          100% {
            transform: translateX(-100%);
          }
        }
      `}</style>
    </section>
  );
}
