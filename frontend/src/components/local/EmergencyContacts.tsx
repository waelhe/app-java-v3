'use client';

import React from 'react';
import { Phone, Ambulance, Flame, Shield, AlertTriangle, Car, Zap, Heart } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';

interface EmergencyContact {
  id: string;
  name: string;
  nameEn: string;
  number: string;
  icon: React.ElementType;
  color: string;
  bgColor: string;
  gradient: string;
  image: string;
}

const allContacts: EmergencyContact[] = [
  { 
    id: 'emergency', 
    name: 'طوارئ عامة', 
    nameEn: 'Emergency', 
    number: '112', 
    icon: AlertTriangle, 
    color: 'text-white',
    bgColor: 'bg-red-500/90',
    gradient: 'from-red-500 to-rose-600',
    image: 'https://images.unsplash.com/photo-1587745416684-47953f16f02f?auto=format&fit=crop&w=400&q=80'
  },
  { 
    id: 'ambulance', 
    name: 'إسعاف', 
    nameEn: 'Ambulance', 
    number: '110', 
    icon: Ambulance, 
    color: 'text-white',
    bgColor: 'bg-rose-500/90',
    gradient: 'from-rose-500 to-pink-600',
    image: 'https://images.unsplash.com/photo-1587351021759-3e566b6af7cc?auto=format&fit=crop&w=400&q=80'
  },
  { 
    id: 'fire', 
    name: 'إطفاء', 
    nameEn: 'Fire Dept', 
    number: '113', 
    icon: Flame, 
    color: 'text-white',
    bgColor: 'bg-orange-500/90',
    gradient: 'from-orange-500 to-amber-600',
    image: 'https://images.unsplash.com/photo-1597466599360-3b9775841aec?auto=format&fit=crop&w=400&q=80'
  },
  { 
    id: 'police', 
    name: 'شرطة', 
    nameEn: 'Police', 
    number: '112', 
    icon: Shield, 
    color: 'text-white',
    bgColor: 'bg-blue-500/90',
    gradient: 'from-blue-500 to-indigo-600',
    image: 'https://images.unsplash.com/photo-1453873531674-2151bcd01707?auto=format&fit=crop&w=400&q=80'
  },
  { 
    id: 'electricity', 
    name: 'كهرباء', 
    nameEn: 'Electricity', 
    number: '118', 
    icon: Zap, 
    color: 'text-white',
    bgColor: 'bg-yellow-500/90',
    gradient: 'from-yellow-500 to-amber-500',
    image: 'https://images.unsplash.com/photo-1473341304170-971dccb5ac1e?auto=format&fit=crop&w=400&q=80'
  },
  { 
    id: 'traffic', 
    name: 'مرور', 
    nameEn: 'Traffic', 
    number: '115', 
    icon: Car, 
    color: 'text-white',
    bgColor: 'bg-indigo-500/90',
    gradient: 'from-indigo-500 to-violet-600',
    image: 'https://images.unsplash.com/photo-1449965408869-ebd3fee56a57?auto=format&fit=crop&w=400&q=80'
  },
];

export default function EmergencyContacts() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '📞 أرقام الطوارئ' : '📞 Emergency Numbers'}
        </h2>

        {/* Grid - Simple Clean Design */}
        <div className="grid grid-cols-3 sm:grid-cols-6 gap-2">
          {allContacts.map((contact) => {
            const Icon = contact.icon;
            return (
              <a
                key={contact.id}
                href={`tel:${contact.number}`}
                className="group flex flex-col items-center justify-center p-3 bg-gray-50 hover:bg-gray-100 rounded-xl transition-colors"
              >
                {/* Icon */}
                <div className={`p-2 rounded-lg bg-gradient-to-br ${contact.gradient} mb-2`}>
                  <Icon className="w-4 h-4 text-white" />
                </div>

                {/* Name */}
                <h3 className="text-[11px] font-medium text-gray-600 text-center mb-0.5">
                  {isArabic ? contact.name : contact.nameEn}
                </h3>

                {/* Number */}
                <span className="text-base font-bold text-gray-900">
                  {contact.number}
                </span>
              </a>
            );
          })}
        </div>
      </div>
    </section>
  );
}
